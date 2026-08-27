package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.coordinator.distributed.RemoteWorkerDirectory;
import io.scenariomesh.coordinator.distributed.RemoteWorkerRegistration;
import io.scenariomesh.coordinator.distributed.RemoteWorkerServer;
import io.scenariomesh.coordinator.distributed.RemoteWorkerSession;
import io.scenariomesh.coordinator.distributed.WorkerRegistrationValidator;

import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Authenticated remote-worker sessions proven during Maven preflight and retained for the
 * subsequent ScenarioMesh execution phase. Keeping the exact sessions prevents a successful
 * readiness probe from being followed by an unproven reconnect after native Maven is suppressed.
 */
public final class PreparedRemoteWorkers implements AutoCloseable {
    private final RemoteWorkerServer server;
    private final RemoteWorkerDirectory directory;
    private final List<RemoteWorkerSession> sessions;
    private boolean transferred;

    private PreparedRemoteWorkers(RemoteWorkerServer server,
                                  RemoteWorkerDirectory directory,
                                  List<RemoteWorkerSession> sessions) {
        this.server = server;
        this.directory = directory;
        this.sessions = new ArrayList<>(sessions);
    }

    public static PreparedRemoteWorkers prepare(ScenarioMeshConfig config,
                                                Set<String> requiredAdapterIds,
                                                Set<String> requiredEngineIds,
                                                Consumer<String> progress) throws Exception {
        Objects.requireNonNull(config, "config");
        if (!config.distributed().remote()) {
            throw new IllegalArgumentException("PreparedRemoteWorkers requires workers.mode=remote");
        }
        Set<String> adapters = Set.copyOf(requiredAdapterIds == null ? Set.of() : requiredAdapterIds);
        Set<String> engines = Set.copyOf(requiredEngineIds == null ? Set.of() : requiredEngineIds);
        Consumer<String> log = progress == null ? ignored -> { } : progress;
        WorkerRegistrationValidator validator = new WorkerRegistrationValidator();
        RemoteWorkerDirectory directory = new RemoteWorkerDirectory(config.workerTaskTimeout().multipliedBy(2));
        RemoteWorkerServer server = new RemoteWorkerServer(
                InetAddress.getByName(config.distributed().bindHost()),
                config.distributed().bindPort(), config.distributed().token(), validator, directory);
        List<RemoteWorkerSession> sessions = new ArrayList<>();
        try {
            log.accept("ScenarioMesh remote preflight listening on " + config.distributed().bindHost() + ":"
                    + server.address().getPort() + "; waiting for up to " + config.workerCount()
                    + " authenticated worker process(es). Token is intentionally not logged.");
            while (sessions.size() < config.workerCount()) {
                try {
                    RemoteWorkerSession session = server.accept(config.distributed().registrationTimeout());
                    if (sessions.stream().anyMatch(existing -> existing.registration().workerId()
                            .equals(session.registration().workerId()))) {
                        server.disconnected(session);
                        continue;
                    }
                    sessions.add(session);
                    log.accept("ScenarioMesh remote preflight registered " + session.registration().workerId()
                            + " agent=" + session.registration().metadata().getOrDefault("agentId", "unknown")
                            + " java=" + session.registration().javaFeature());
                } catch (SocketTimeoutException timeout) {
                    break;
                }
            }

            if (sessions.size() < config.minimumReadyWorkers()) {
                throw new IllegalStateException("Only " + sessions.size() + " of " + config.workerCount()
                        + " remote workers registered; minimum required is " + config.minimumReadyWorkers());
            }
            verifyEveryWorkerCoverage(
                    sessions.stream().map(RemoteWorkerSession::registration).toList(), adapters, engines);
            log.accept("ScenarioMesh remote preflight proved " + sessions.size() + " authenticated worker(s); each can execute adapters="
                    + adapters + ", engines=" + engines + ".");
            return new PreparedRemoteWorkers(server, directory, sessions);
        } catch (Exception exception) {
            for (RemoteWorkerSession session : sessions) server.disconnected(session);
            try { server.close(); } catch (Exception ignored) { }
            throw exception;
        }
    }

    static void verifyEveryWorkerCoverage(List<RemoteWorkerRegistration> registrations,
                                          Set<String> requiredAdapterIds,
                                          Set<String> requiredEngineIds) {
        for (RemoteWorkerRegistration registration : registrations) {
            Set<String> missingAdapters = new HashSet<>(requiredAdapterIds);
            missingAdapters.removeAll(registration.adapterIds());
            Set<String> missingEngines = new HashSet<>(requiredEngineIds);
            missingEngines.removeAll(registration.engineIds());
            if (!missingAdapters.isEmpty() || !missingEngines.isEmpty()) {
                throw new IllegalStateException("Remote worker " + registration.workerId()
                        + " cannot prove the complete selected runtime while capability-aware heterogeneous scheduling is disabled; missing adapters="
                        + missingAdapters + ", missing JUnit Platform engines=" + missingEngines);
            }
        }
    }

    public int workerCount() {
        return sessions.size();
    }

    synchronized PreparedState transfer() {
        if (transferred) throw new IllegalStateException("Prepared remote workers were already transferred");
        transferred = true;
        return new PreparedState(server, directory, List.copyOf(sessions));
    }

    @Override
    public synchronized void close() {
        if (transferred) return;
        for (RemoteWorkerSession session : List.copyOf(sessions)) {
            try { server.disconnected(session); } catch (Exception ignored) { }
        }
        sessions.clear();
        try { server.close(); } catch (Exception ignored) { }
    }

    record PreparedState(RemoteWorkerServer server,
                         RemoteWorkerDirectory directory,
                         List<RemoteWorkerSession> sessions) {
        PreparedState {
            sessions = List.copyOf(sessions);
        }
    }
}
