package io.scenariomesh.coordinator;

import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.coordinator.distributed.RemoteWorkerDirectory;
import io.scenariomesh.coordinator.distributed.RemoteWorkerRegistration;
import io.scenariomesh.coordinator.distributed.RemoteWorkerServer;
import io.scenariomesh.coordinator.distributed.RemoteWorkerSession;
import io.scenariomesh.coordinator.distributed.WorkerRegistrationValidator;

import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Authenticated remote-worker sessions proven during Maven preflight and retained for execution. */
public final class PreparedRemoteWorkers implements AutoCloseable {
    private static final Duration REMOTE_LIVENESS_TIMEOUT = Duration.ofSeconds(20);
    private static final String JUNIT_PLATFORM_ADAPTER = "junit-platform";
    private final RemoteWorkerServer server;
    private final RemoteWorkerDirectory directory;
    private final List<RemoteWorkerSession> sessions;
    private final AutoCloseable serverLease;
    private boolean transferred;
    private boolean closed;

    private PreparedRemoteWorkers(RemoteWorkerServer server, RemoteWorkerDirectory directory,
                                  List<RemoteWorkerSession> sessions, AutoCloseable serverLease) {
        this.server = Objects.requireNonNull(server, "server");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.sessions = new ArrayList<>(sessions);
        this.serverLease = Objects.requireNonNull(serverLease, "serverLease");
    }

    public static PreparedRemoteWorkers prepare(ScenarioMeshConfig config,
                                                Set<String> requiredAdapterIds,
                                                Set<String> requiredEngineIds,
                                                Consumer<String> progress) throws Exception {
        return prepare(config, requiredAdapterIds, requiredEngineIds, null, progress);
    }

    /**
     * Prepares one execution-specific remote cohort and, when an expected runtime fingerprint is supplied,
     * requires the retained workers to match the exact runtime identity established by Maven preflight.
     */
    public static PreparedRemoteWorkers prepare(ScenarioMeshConfig config,
                                                Set<String> requiredAdapterIds,
                                                Set<String> requiredEngineIds,
                                                String expectedRuntimeFingerprint,
                                                Consumer<String> progress) throws Exception {
        return prepareAll(config, List.of(new ExecutionRequirement(
                "default", requiredAdapterIds, requiredEngineIds, expectedRuntimeFingerprint)), progress).get(0);
    }

    /**
     * Prepares independent worker cohorts for multiple Maven test executions behind one authenticated server.
     *
     * <p>All cohorts are proven before native Maven execution can be suppressed. A worker is assigned to only
     * one execution cohort, so execution-specific JVM/system-property/environment state cannot leak between
     * Maven executions. Sharing the server avoids fixed bind-port collisions while retaining one-shot worker
     * process semantics for each execution.</p>
     */
    public static List<PreparedRemoteWorkers> prepareAll(ScenarioMeshConfig config,
                                                          List<ExecutionRequirement> requirements,
                                                          Consumer<String> progress) throws Exception {
        Objects.requireNonNull(config, "config");
        if (!config.distributed().remote()) {
            throw new IllegalArgumentException("PreparedRemoteWorkers requires workers.mode=remote");
        }
        List<ExecutionRequirement> required = List.copyOf(requirements == null ? List.of() : requirements);
        if (required.isEmpty()) throw new IllegalArgumentException("at least one remote execution requirement is required");

        Consumer<String> log = progress == null ? ignored -> { } : progress;
        WorkerRegistrationValidator validator = new WorkerRegistrationValidator();
        RemoteWorkerDirectory directory = new RemoteWorkerDirectory(REMOTE_LIVENESS_TIMEOUT);
        RemoteWorkerServer server = new RemoteWorkerServer(
                InetAddress.getByName(config.distributed().bindHost()), config.distributed().bindPort(),
                config.distributed().token(), validator, directory, config.distributed().tls());
        List<List<RemoteWorkerSession>> cohorts = new ArrayList<>();
        for (int i = 0; i < required.size(); i++) cohorts.add(new ArrayList<>());
        Set<String> seenWorkerIds = new HashSet<>();
        int targetRegistrations = Math.multiplyExact(config.workerCount(), required.size());

        try {
            log.accept("ScenarioMesh remote preflight listening on " + config.distributed().bindHost() + ":"
                    + server.address().getPort() + " transport=" + (server.tlsEnabled() ? "tls" : "loopback-plain")
                    + "; proving " + required.size() + " Maven execution cohort(s), up to " + targetRegistrations
                    + " authenticated worker process(es). Token is intentionally not logged.");

            while (sessionCount(cohorts) < targetRegistrations) {
                RemoteWorkerSession session;
                try {
                    session = server.accept(config.distributed().registrationTimeout());
                } catch (SocketTimeoutException timeout) {
                    break;
                }
                RemoteWorkerRegistration registration = session.registration();
                if (!seenWorkerIds.add(registration.workerId())) {
                    server.disconnected(session);
                    continue;
                }
                int cohort = selectCohort(required, registrations(cohorts), registration,
                        config.workerCount(), config.minimumReadyWorkers(), validator);
                if (cohort < 0) {
                    log.accept("ScenarioMesh remote preflight rejected " + registration.workerId()
                            + " because no unfinished Maven execution cohort has a matching runtime fingerprint/capability need.");
                    server.disconnected(session);
                    continue;
                }
                cohorts.get(cohort).add(session);
                ExecutionRequirement execution = required.get(cohort);
                log.accept("ScenarioMesh remote preflight registered " + registration.workerId()
                        + " for Maven execution '" + execution.executionId() + "'"
                        + " agent=" + registration.metadata().getOrDefault("agentId", "unknown")
                        + " java=" + registration.javaFeature()
                        + " cohort=" + cohorts.get(cohort).size() + "/" + config.workerCount());
            }

            for (int index = 0; index < required.size(); index++) {
                ExecutionRequirement execution = required.get(index);
                List<RemoteWorkerSession> cohort = cohorts.get(index);
                if (cohort.size() < config.minimumReadyWorkers()) {
                    throw new IllegalStateException("Maven execution '" + execution.executionId() + "' has only "
                            + cohort.size() + " of " + config.workerCount()
                            + " equivalent remote workers; minimum required is " + config.minimumReadyWorkers());
                }
                List<RemoteWorkerRegistration> registrations = cohort.stream()
                        .map(RemoteWorkerSession::registration).toList();
                verifyRuntimeFingerprint(registrations, execution.expectedRuntimeFingerprint(), execution.executionId());
                verifyCapabilityCoverage(registrations, execution.requiredAdapterIds(), execution.requiredEngineIds());
                log.accept("ScenarioMesh remote preflight proved Maven execution '" + execution.executionId() + "' with "
                        + cohort.size() + " authenticated equivalent worker(s), adapters="
                        + execution.requiredAdapterIds() + ", engines=" + execution.requiredEngineIds() + ".");
            }

            SharedServer shared = new SharedServer(server, required.size());
            List<PreparedRemoteWorkers> result = new ArrayList<>(required.size());
            for (List<RemoteWorkerSession> cohort : cohorts) {
                result.add(new PreparedRemoteWorkers(server, directory, cohort, shared::release));
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            for (List<RemoteWorkerSession> cohort : cohorts) {
                for (RemoteWorkerSession session : cohort) {
                    try { server.disconnected(session); } catch (Exception ignored) { }
                }
            }
            try { server.close(); } catch (Exception ignored) { }
            throw exception;
        }
    }

    static int selectCohort(List<ExecutionRequirement> requirements,
                            List<List<RemoteWorkerRegistration>> cohorts,
                            RemoteWorkerRegistration registration,
                            int workerCount,
                            int minimumReadyWorkers,
                            WorkerRegistrationValidator validator) {
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(cohorts, "cohorts");
        Objects.requireNonNull(registration, "registration");
        if (requirements.size() != cohorts.size()) throw new IllegalArgumentException("requirements/cohorts size mismatch");
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < requirements.size(); index++) {
            ExecutionRequirement requirement = requirements.get(index);
            List<RemoteWorkerRegistration> cohort = cohorts.get(index);
            if (cohort.size() >= workerCount || !runtimeMatches(requirement, registration)) continue;
            int score = 0;
            if (addsMissingCapability(requirement, cohort, registration, validator)) score += 2_000;
            if (cohort.size() < minimumReadyWorkers) score += 1_000;
            score += workerCount - cohort.size();
            if (score > bestScore) {
                best = index;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean runtimeMatches(ExecutionRequirement requirement, RemoteWorkerRegistration registration) {
        String expected = requirement.expectedRuntimeFingerprint();
        return expected == null || expected.isBlank() || expected.equals(registration.runtimeFingerprint());
    }

    private static boolean addsMissingCapability(ExecutionRequirement requirement,
                                                 List<RemoteWorkerRegistration> cohort,
                                                 RemoteWorkerRegistration registration,
                                                 WorkerRegistrationValidator validator) {
        for (String adapter : requirement.requiredAdapterIds()) {
            boolean alreadyCovered = cohort.stream().anyMatch(worker -> validator.canRun(worker, adapter, null));
            if (!alreadyCovered && validator.canRun(registration, adapter, null)) return true;
        }
        for (String engine : requirement.requiredEngineIds()) {
            boolean alreadyCovered = cohort.stream().anyMatch(worker -> validator.canRun(worker, JUNIT_PLATFORM_ADAPTER, engine));
            if (!alreadyCovered && validator.canRun(registration, JUNIT_PLATFORM_ADAPTER, engine)) return true;
        }
        return requirement.requiredAdapterIds().isEmpty() && requirement.requiredEngineIds().isEmpty();
    }

    private static int sessionCount(List<List<RemoteWorkerSession>> cohorts) {
        int count = 0;
        for (List<RemoteWorkerSession> cohort : cohorts) count += cohort.size();
        return count;
    }

    private static List<List<RemoteWorkerRegistration>> registrations(List<List<RemoteWorkerSession>> cohorts) {
        List<List<RemoteWorkerRegistration>> values = new ArrayList<>(cohorts.size());
        for (List<RemoteWorkerSession> cohort : cohorts) {
            values.add(cohort.stream().map(RemoteWorkerSession::registration).toList());
        }
        return List.copyOf(values);
    }

    private static void verifyRuntimeFingerprint(List<RemoteWorkerRegistration> registrations,
                                                 String expectedRuntimeFingerprint,
                                                 String executionId) {
        if (expectedRuntimeFingerprint == null || expectedRuntimeFingerprint.isBlank()) return;
        for (RemoteWorkerRegistration registration : registrations) {
            if (!expectedRuntimeFingerprint.equals(registration.runtimeFingerprint())) {
                throw new IllegalStateException("Remote worker " + registration.workerId()
                        + " does not match Maven execution '" + executionId + "' runtime fingerprint");
            }
        }
    }

    static void verifyCapabilityCoverage(List<RemoteWorkerRegistration> registrations,
                                         Set<String> requiredAdapterIds,
                                         Set<String> requiredEngineIds) {
        Objects.requireNonNull(registrations, "registrations");
        WorkerRegistrationValidator validator = new WorkerRegistrationValidator();
        for (String adapterId : requiredAdapterIds) {
            boolean covered = registrations.stream().anyMatch(registration -> validator.canRun(registration, adapterId, null));
            if (!covered) throw new IllegalStateException("No prepared remote worker can execute required adapter " + adapterId);
        }
        for (String engineId : requiredEngineIds) {
            boolean covered = registrations.stream().anyMatch(registration ->
                    validator.canRun(registration, JUNIT_PLATFORM_ADAPTER, engineId));
            if (!covered) {
                throw new IllegalStateException("No prepared remote worker can execute required JUnit Platform engine "
                        + engineId + " with adapter " + JUNIT_PLATFORM_ADAPTER);
            }
        }
    }

    public int workerCount() { return sessions.size(); }

    synchronized PreparedState transfer() {
        if (closed) throw new IllegalStateException("Prepared remote workers were already closed");
        if (transferred) throw new IllegalStateException("Prepared remote workers were already transferred");
        transferred = true;
        return new PreparedState(server, directory, List.copyOf(sessions), serverLease);
    }

    @Override public synchronized void close() {
        if (closed || transferred) return;
        closed = true;
        for (RemoteWorkerSession session : List.copyOf(sessions)) {
            try { server.disconnected(session); } catch (Exception ignored) { }
        }
        sessions.clear();
        try { serverLease.close(); } catch (Exception ignored) { }
    }

    public record ExecutionRequirement(String executionId,
                                       Set<String> requiredAdapterIds,
                                       Set<String> requiredEngineIds,
                                       String expectedRuntimeFingerprint) {
        public ExecutionRequirement {
            executionId = executionId == null || executionId.isBlank() ? "<unnamed>" : executionId;
            requiredAdapterIds = Set.copyOf(requiredAdapterIds == null ? Set.of() : requiredAdapterIds);
            requiredEngineIds = Set.copyOf(requiredEngineIds == null ? Set.of() : requiredEngineIds);
            expectedRuntimeFingerprint = expectedRuntimeFingerprint == null || expectedRuntimeFingerprint.isBlank()
                    ? null : expectedRuntimeFingerprint;
        }
    }

    record PreparedState(RemoteWorkerServer server, RemoteWorkerDirectory directory,
                         List<RemoteWorkerSession> sessions, AutoCloseable serverLease) {
        PreparedState {
            sessions = List.copyOf(sessions);
            Objects.requireNonNull(serverLease, "serverLease");
        }
    }

    private static final class SharedServer {
        private final RemoteWorkerServer server;
        private int references;
        private boolean closed;

        private SharedServer(RemoteWorkerServer server, int references) {
            this.server = Objects.requireNonNull(server, "server");
            if (references < 1) throw new IllegalArgumentException("shared remote server requires at least one reference");
            this.references = references;
        }

        private synchronized void release() throws Exception {
            if (closed) return;
            references--;
            if (references > 0) return;
            closed = true;
            server.close();
        }
    }
}
