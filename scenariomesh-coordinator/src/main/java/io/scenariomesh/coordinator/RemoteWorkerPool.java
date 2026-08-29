package io.scenariomesh.coordinator;

import io.scenariomesh.config.DistributedConfig;
import io.scenariomesh.coordinator.distributed.DistributedWorkAuthority;
import io.scenariomesh.coordinator.distributed.LeaseRegistry;
import io.scenariomesh.coordinator.distributed.LeasedResponseReader;
import io.scenariomesh.coordinator.distributed.RemoteWorkerDirectory;
import io.scenariomesh.coordinator.distributed.RemoteWorkerRegistration;
import io.scenariomesh.coordinator.distributed.RemoteWorkerServer;
import io.scenariomesh.coordinator.distributed.RemoteWorkerSession;
import io.scenariomesh.coordinator.distributed.WorkerRegistrationValidator;
import io.scenariomesh.coordinator.distributed.WorkerRegistrationValidator.CapabilityMismatchException;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioId;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.Ports.SchedulingStrategy;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerTelemetry;
import io.scenariomesh.scheduler.FifoSchedulingStrategy;

import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Remote-worker execution pool. CI owns machines/executors; ScenarioMesh owns authenticated test scheduling. */
final class RemoteWorkerPool implements AutoCloseable {
    private static final String EXECUTION_SCOPE_ID = "executionScopeId";
    private static final String REQUIRED_ENGINE_ID = "requiredEngineId";
    private static final Duration REMOTE_LIVENESS_TIMEOUT = Duration.ofSeconds(20);

    private final RunRequest request;
    private final RunLogger logger;
    private final ExecutionResultValidator resultValidator = new ExecutionResultValidator();
    private final WorkerRegistrationValidator registrationValidator = new WorkerRegistrationValidator();
    private final DistributedWorkAuthority workAuthority;
    private final LeasedResponseReader responseReader;
    private final RemoteWorkerDirectory directory;
    private final RemoteWorkerServer server;
    private final boolean preparedOwnership;
    private final CopyOnWriteArrayList<RemoteWorkerSession> sessions = new CopyOnWriteArrayList<>();
    private final Map<ScenarioId, Integer> attempts = new ConcurrentHashMap<>();
    private final Object replacementLock = new Object();

    RemoteWorkerPool(RunRequest request, RunLogger logger) throws Exception {
        this.request = request;
        this.logger = logger;
        this.preparedOwnership = false;
        DistributedConfig distributed = request.config().distributed();
        if (!distributed.remote()) throw new IllegalArgumentException("RemoteWorkerPool requires workers.mode=remote");
        this.workAuthority = new DistributedWorkAuthority(new LeaseRegistry(request.config().workerTaskTimeout().multipliedBy(2)));
        this.responseReader = new LeasedResponseReader(workAuthority);
        this.directory = new RemoteWorkerDirectory(REMOTE_LIVENESS_TIMEOUT);
        this.server = new RemoteWorkerServer(InetAddress.getByName(distributed.bindHost()), distributed.bindPort(),
                distributed.token(), registrationValidator, directory, distributed.tls());
        logger.progress("Remote worker coordinator listening on " + distributed.bindHost() + ":" + server.address().getPort()
                + " transport=" + (server.tlsEnabled() ? "tls" : "loopback-plain") + "; waiting for "
                + request.config().workerCount() + " authenticated worker process(es). Token is intentionally not logged.");
        try { acceptInitialWorkers(); } catch (Exception exception) { close(); throw exception; }
    }

    RemoteWorkerPool(RunRequest request, RunLogger logger, PreparedRemoteWorkers prepared) {
        this.request = request;
        this.logger = logger;
        this.preparedOwnership = true;
        if (!request.config().distributed().remote()) throw new IllegalArgumentException("Prepared remote workers require workers.mode=remote");
        if (prepared == null) throw new IllegalArgumentException("prepared remote workers are required");
        this.workAuthority = new DistributedWorkAuthority(new LeaseRegistry(request.config().workerTaskTimeout().multipliedBy(2)));
        this.responseReader = new LeasedResponseReader(workAuthority);
        PreparedRemoteWorkers.PreparedState state = prepared.transfer();
        this.directory = state.directory();
        this.server = state.server();
        this.sessions.addAll(state.sessions());
        if (sessions.isEmpty()) throw new IllegalStateException("Prepared remote worker set is empty");
        logger.progress("Using " + sessions.size() + " authenticated remote worker process(es) proven during Maven preflight; no reconnect is required.");
    }

    List<ExecutionResult> execute(List<ScenarioTask> tasks) throws InterruptedException {
        verifyTaskCoverage(tasks);
        WorkPlan workPlan = WorkPlan.from(tasks);
        SchedulingStrategy scheduler = new FifoSchedulingStrategy();
        scheduler.load(workPlan.representatives());
        ConcurrentLinkedQueue<ExecutionResult> results = new ConcurrentLinkedQueue<>();
        RunProgress progress = new RunProgress(tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(sessions.size());
        logger.progress("Distributed scheduler loaded " + workPlan.units().size() + " work unit(s) for " + tasks.size()
                + " logical task(s); " + sessions.size() + " remote worker(s) ready.");
        for (RemoteWorkerSession session : List.copyOf(sessions)) executor.submit(() -> loop(session, scheduler, workPlan, results, progress));
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        return List.copyOf(results);
    }

    private void verifyTaskCoverage(List<ScenarioTask> tasks) {
        for (ScenarioTask task : tasks) {
            boolean covered = sessions.stream().anyMatch(session -> canRun(session.registration(), task));
            if (!covered) {
                throw new IllegalStateException("No registered remote worker can execute task " + task.id().value()
                        + " using adapter=" + task.adapterId() + ", engine=" + requiredEngineId(task));
            }
        }
    }

    static boolean canRun(WorkerRegistrationValidator validator,
                          RemoteWorkerRegistration registration,
                          ScenarioTask task) {
        return validator.canRun(registration, task.adapterId(), requiredEngineId(task));
    }

    private boolean canRun(RemoteWorkerRegistration registration, ScenarioTask task) {
        return canRun(registrationValidator, registration, task);
    }

    static String requiredEngineId(ScenarioTask task) {
        String engineId = task.metadata().get(REQUIRED_ENGINE_ID);
        return engineId == null || engineId.isBlank() ? null : engineId;
    }

    private void loop(RemoteWorkerSession initialSession, SchedulingStrategy scheduler, WorkPlan workPlan,
                      ConcurrentLinkedQueue<ExecutionResult> results, RunProgress progress) {
        RemoteWorkerSession session = initialSession;
        String executionLaneId = "remote-lane:" + initialSession.registration().workerId();
        int tasksOnWorker = 0;
        for (;;) {
            try { refreshIdleLiveness(session); }
            catch (Exception stale) {
                retire(session, "idle liveness failure: " + safeMessage(stale));
                return;
            }
            RemoteWorkerSession currentSession = session;
            ScenarioTask representative = scheduler.nextEligible(
                    executionLaneId, candidate -> canRun(currentSession.registration(), candidate));
            if (representative == null) { gracefulStop(session); return; }
            WorkUnit unit = workPlan.required(representative.id());
            String workerId = session.registration().workerId();
            int attempt = attempts.merge(representative.id(), 1, Integer::sum);
            int busy = progress.busy.addAndGet(unit.tasks().size());
            logger.progress(workerId + " RUN " + unit.label() + " attempt=" + attempt + " leaves=" + unit.tasks().size()
                    + " | completed=" + progress.completed.get() + "/" + progress.total + " busy=" + busy
                    + " queuedUnits=" + scheduler.queued());

            Instant started = Instant.now();
            List<ExecutionResult> unitResults;
            WorkerTelemetry telemetry = null;
            try {
                registrationValidator.requireCanRun(session.registration(), representative.adapterId(), requiredEngineId(representative));
                directory.claimSlot(workerId, started);
                Envelope run = workAuthority.issueRun(representative.id().value(), workerId, attempt, unit.tasks(), started);
                logger.schedulerDecision(workerId, representative, run, scheduler.queued(), "lifecycle-safe compatible work selected");
                session.write(run);
                Envelope response = responseReader.readTerminal(workerId, request.config().workerTaskTimeout(), session::read,
                        heartbeatAt -> directory.heartbeat(workerId, heartbeatAt));
                if (response == null) {
                    unitResults = failures(unit.tasks(), workerId, attempt, started, "Remote worker disconnected before returning a work-unit result");
                } else {
                    workAuthority.acceptResult(workerId, response, Instant.now());
                    unitResults = resultValidator.validateBatchOrFailures(unit.tasks(), workerId, attempt, started, response);
                    if (unitResults.stream().noneMatch(this::isProtocolValidationFailure)) telemetry = response.telemetry();
                }
            } catch (CapabilityMismatchException exception) {
                unitResults = protocolFailures(unit.tasks(), workerId, attempt, started, "Remote worker capability mismatch: " + safeMessage(exception));
            } catch (LeaseRegistry.StaleLeaseException exception) {
                unitResults = protocolFailures(unit.tasks(), workerId, attempt, started,
                        "Rejected stale or non-authoritative remote worker result: " + safeMessage(exception));
            } catch (SocketTimeoutException exception) {
                unitResults = failures(unit.tasks(), workerId, attempt, started,
                        "Remote worker exceeded work-unit timeout " + request.config().workerTaskTimeout());
            } catch (Exception exception) {
                unitResults = failures(unit.tasks(), workerId, attempt, started, safeMessage(exception));
            } finally {
                try { directory.releaseSlot(workerId); } catch (Exception ignored) { }
            }

            progress.busy.addAndGet(-unit.tasks().size());
            if (retryableUnit(unit, unitResults) && attempt <= request.config().infrastructureRetries()) {
                scheduler.requeue(representative);
                logger.progress(workerId + " RETRY " + unit.label() + " | nextAttempt=" + (attempt + 1));
                RemoteWorkerSession replacement = replace(session, "retryable worker/transport failure");
                if (replacement == null) return;
                session = replacement;
                tasksOnWorker = 0;
                continue;
            }

            attempts.remove(representative.id());
            for (ExecutionResult result : unitResults) {
                results.add(result);
                int completed = progress.completed.incrementAndGet();
                if (!result.buildSuccessful()) progress.failed.incrementAndGet();
                logger.workerCompleted(workerId, result, completed, progress.failed.get(), progress.busy.get(), progress.total);
            }

            if (unitResults.stream().anyMatch(this::requiresWorkerRetirement)) {
                String reason = unitResults.stream().anyMatch(this::isProtocolValidationFailure) ? "protocol result validation failure" : "worker failure";
                if (scheduler.queued() == 0) { retire(session, reason); return; }
                RemoteWorkerSession replacement = replace(session, reason);
                if (replacement == null) return;
                session = replacement;
                tasksOnWorker = 0;
                continue;
            }

            tasksOnWorker += unit.tasks().size();
            String recycleReason = recycleReason(tasksOnWorker, telemetry);
            if (recycleReason != null) {
                if (scheduler.queued() == 0) { gracefulStop(session); return; }
                RemoteWorkerSession replacement = replace(session, recycleReason);
                if (replacement == null) return;
                session = replacement;
                tasksOnWorker = 0;
            }
        }
    }

    private void refreshIdleLiveness(RemoteWorkerSession session) throws Exception {
        String workerId = session.registration().workerId();
        for (;;) {
            Envelope envelope = session.readAvailable();
            if (envelope == null) break;
            if (!workerId.equals(envelope.workerId())) throw new IllegalStateException("idle worker identity mismatch");
            if (envelope.type() == Protocol.Type.PRESENCE) {
                directory.heartbeat(workerId, Instant.now());
                continue;
            }
            throw new IllegalStateException("unexpected idle worker message " + envelope.type());
        }
        if (directory.staleWorkers(Instant.now()).contains(workerId)) {
            throw new IllegalStateException("worker presence heartbeat is stale");
        }
    }

    private void acceptInitialWorkers() throws Exception {
        DistributedConfig distributed = request.config().distributed();
        try {
            while (sessions.size() < request.config().workerCount()) {
                RemoteWorkerSession session = server.accept(distributed.registrationTimeout());
                if (sessions.stream().anyMatch(existing -> existing.registration().workerId().equals(session.registration().workerId()))) {
                    server.disconnected(session);
                    continue;
                }
                sessions.add(session);
                String agent = session.registration().metadata().getOrDefault("agentId", "unknown");
                logger.workerLifecycle("WORKER_REGISTERED", session.registration().workerId(), agent, "remote worker ready");
                logger.progress(session.registration().workerId() + " REMOTE READY agent=" + agent + " java=" + session.registration().javaFeature());
            }
        } catch (SocketTimeoutException timeout) {
            if (sessions.size() < request.config().minimumReadyWorkers()) {
                throw new IllegalStateException("Only " + sessions.size() + " of " + request.config().workerCount()
                        + " remote workers registered; minimum required is " + request.config().minimumReadyWorkers(), timeout);
            }
            logger.progress("Starting distributed run in degraded capacity with " + sessions.size() + "/"
                    + request.config().workerCount() + " remote workers ready.");
        }
    }

    private RemoteWorkerSession replace(RemoteWorkerSession oldSession, String reason) {
        synchronized (replacementLock) {
            String oldId = oldSession.registration().workerId();
            retire(oldSession, reason);
            if (preparedOwnership) {
                logger.progress("Prepared worker " + oldId + " will not be replaced after native Maven suppression; replacement capability has not been preflight-proven.");
                return null;
            }
            try {
                RemoteWorkerSession replacement = server.accept(request.config().distributed().registrationTimeout());
                sessions.add(replacement);
                String agent = replacement.registration().metadata().getOrDefault("agentId", "unknown");
                logger.workerLifecycle("WORKER_REPLACED", replacement.registration().workerId(), agent, "replaced " + oldId + " after " + reason);
                logger.progress(replacement.registration().workerId() + " REPLACED " + oldId + " after " + reason + " and is ready for queued work.");
                return replacement;
            } catch (Exception exception) {
                logger.progress("No replacement remote worker registered for " + oldId + ": " + safeMessage(exception) + "; remaining workers will continue.");
                return null;
            }
        }
    }

    private void retire(RemoteWorkerSession session, String reason) {
        sessions.remove(session);
        String workerId = session.registration().workerId();
        directory.remove(workerId);
        try { session.close(); } catch (Exception ignored) { }
        logger.workerLifecycle("WORKER_RETIRED", workerId,
                session.registration().metadata().getOrDefault("agentId", "unknown"), reason);
        logger.progress(workerId + " REMOTE RETIRED after " + reason + ".");
    }

    private void gracefulStop(RemoteWorkerSession session) {
        String workerId = session.registration().workerId();
        try {
            directory.beginDrain(workerId);
            logger.workerLifecycle("WORKER_DRAINING", workerId,
                    session.registration().metadata().getOrDefault("agentId", "unknown"), "no new work will be assigned");
            session.write(Envelope.drain(workerId));
            requireControlAck(session, request.config().workerShutdownTimeout(), "DRAIN");
            session.write(Envelope.stop(workerId));
            requireControlAck(session, request.config().workerShutdownTimeout(), "STOP");
            logger.workerLifecycle("WORKER_STOPPED", workerId,
                    session.registration().metadata().getOrDefault("agentId", "unknown"), "graceful drain and stop complete");
        } catch (Exception exception) {
            logger.progress(workerId + " remote worker graceful drain/STOP did not complete: " + safeMessage(exception));
        }
    }

    private void requireControlAck(RemoteWorkerSession session, Duration timeout, String command) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String workerId = session.registration().workerId();
        for (;;) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException(command + " acknowledgement timeout");
            Envelope response = session.read(Duration.ofNanos(remaining));
            if (response == null) throw new IllegalStateException("worker disconnected before " + command + " ACK");
            if (!workerId.equals(response.workerId())) throw new IllegalStateException("control response identity mismatch");
            if (response.type() == Protocol.Type.PRESENCE) {
                directory.heartbeat(workerId, Instant.now());
                continue;
            }
            if (response.type() != Protocol.Type.ACK) throw new IllegalStateException("expected " + command + " ACK but received " + response.type());
            return;
        }
    }

    private boolean retryableUnit(WorkUnit unit, List<ExecutionResult> results) {
        return !unit.scoped() && unit.tasks().size() == 1 && results.size() == 1
                && (results.get(0).status() == ResultStatus.WORKER_FAILURE || results.get(0).status() == ResultStatus.INFRASTRUCTURE_FAILURE);
    }
    private boolean requiresWorkerRetirement(ExecutionResult result) { return result.status() == ResultStatus.WORKER_FAILURE || isProtocolValidationFailure(result); }
    private boolean isProtocolValidationFailure(ExecutionResult result) { return ExecutionResultValidator.FAILURE_TYPE.equals(result.failureType()); }
    private String recycleReason(int tasksOnWorker, WorkerTelemetry telemetry) {
        if (preparedOwnership) return null;
        if (request.config().taskCountRecyclingEnabled() && tasksOnWorker >= request.config().maxTasksPerWorker()) return "task-count recycling after " + tasksOnWorker + " task(s)";
        if (request.config().heapRecyclingEnabled() && telemetry != null && telemetry.heapUsagePercent() >= request.config().maxHeapUsagePercent()) return "heap recycling at " + telemetry.heapUsagePercent() + "%";
        return null;
    }

    private List<ExecutionResult> failures(List<ScenarioTask> tasks, String workerId, int attempt, Instant started, String message) {
        Instant finished = Instant.now();
        return tasks.stream().map(task -> new ExecutionResult(task.id(), task.displayName(), ResultStatus.WORKER_FAILURE,
                Duration.between(started, finished), new WorkerId(workerId), attempt, started, finished, message, "WorkerFailure")).toList();
    }
    private List<ExecutionResult> protocolFailures(List<ScenarioTask> tasks, String workerId, int attempt, Instant started, String message) {
        Instant finished = Instant.now();
        return tasks.stream().map(task -> new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                Duration.between(started, finished), new WorkerId(workerId), attempt, started, finished, message,
                ExecutionResultValidator.FAILURE_TYPE)).toList();
    }
    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @Override public void close() {
        for (RemoteWorkerSession session : List.copyOf(sessions)) {
            try { gracefulStop(session); } catch (Exception ignored) { }
            try { server.disconnected(session); } catch (Exception ignored) { }
        }
        sessions.clear();
        try { server.close(); } catch (Exception ignored) { }
    }

    private static final class RunProgress {
        private final int total;
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger busy = new AtomicInteger();
        private RunProgress(int total) { this.total = total; }
    }
    private record WorkUnit(ScenarioTask representative, List<ScenarioTask> tasks, boolean scoped, String label) {
        private WorkUnit { tasks = List.copyOf(tasks); }
    }
    private record WorkPlan(List<WorkUnit> units, Map<ScenarioId, WorkUnit> byRepresentative) {
        private WorkPlan { units = List.copyOf(units); byRepresentative = Map.copyOf(byRepresentative); }
        static WorkPlan from(List<ScenarioTask> tasks) {
            Map<String, List<ScenarioTask>> grouped = new LinkedHashMap<>();
            Map<String, Boolean> scopedByKey = new LinkedHashMap<>();
            for (ScenarioTask task : tasks) {
                String scope = task.metadata().get(EXECUTION_SCOPE_ID);
                boolean scoped = scope != null && !scope.isBlank();
                String engine = requiredEngineId(task);
                String key = scoped
                        ? "scope:" + task.adapterId() + ":" + (engine == null ? "" : engine) + ":" + scope
                        : "leaf:" + task.id().value();
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(task);
                scopedByKey.putIfAbsent(key, scoped);
            }
            List<WorkUnit> units = new ArrayList<>();
            Map<ScenarioId, WorkUnit> byRepresentative = new LinkedHashMap<>();
            for (Map.Entry<String, List<ScenarioTask>> entry : grouped.entrySet()) {
                List<ScenarioTask> members = List.copyOf(entry.getValue());
                ScenarioTask representative = members.get(0);
                boolean scoped = scopedByKey.get(entry.getKey());
                String label = scoped ? representative.framework() + " scope " + members.size() + " leaf/leaves" : representative.displayName();
                WorkUnit unit = new WorkUnit(representative, members, scoped, label);
                units.add(unit);
                byRepresentative.put(representative.id(), unit);
            }
            return new WorkPlan(units, byRepresentative);
        }
        List<ScenarioTask> representatives() { return units.stream().map(WorkUnit::representative).toList(); }
        WorkUnit required(ScenarioId id) {
            WorkUnit unit = byRepresentative.get(id);
            if (unit == null) throw new IllegalStateException("No work unit for representative " + id.value());
            return unit;
        }
    }
}
