package io.scenariomesh.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.coordinator.distributed.DistributedWorkAuthority;
import io.scenariomesh.coordinator.distributed.LeaseRegistry;
import io.scenariomesh.coordinator.distributed.LeasedResponseReader;
import io.scenariomesh.coordinator.distributed.RemoteWorkerRegistration;
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
import io.scenariomesh.protocol.ProtocolFrameReader;
import io.scenariomesh.scheduler.FifoSchedulingStrategy;
import io.scenariomesh.workerruntime.JsonCodec;
import io.scenariomesh.workerruntime.WorkerMain;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

final class WorkerPool implements AutoCloseable {
    private static final String EXECUTION_SCOPE_ID = "executionScopeId";

    private final ObjectMapper mapper = JsonCodec.create();
    private final ExecutionResultValidator resultValidator = new ExecutionResultValidator();
    private final WorkerRegistrationValidator registrationValidator = new WorkerRegistrationValidator();
    private final RunRequest request;
    private final Path dir;
    private final String token = UUID.randomUUID().toString();
    private final ServerSocket server;
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, Thread> outputPumps = new ConcurrentHashMap<>();
    private final Map<ScenarioId, Integer> attempts = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<WorkerConnection> connections = new CopyOnWriteArrayList<>();
    private final AtomicInteger workerSequence = new AtomicInteger();
    private final Object replacementLock = new Object();
    private final RunLogger logger;
    private final DistributedWorkAuthority workAuthority;
    private final LeasedResponseReader responseReader;

    WorkerPool(RunRequest request, Path dir, RunLogger logger) throws Exception {
        this.request = request;
        this.dir = dir;
        this.logger = logger;
        this.workAuthority = new DistributedWorkAuthority(
                new LeaseRegistry(request.config().workerTaskTimeout().multipliedBy(2)));
        this.responseReader = new LeasedResponseReader(workAuthority);
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.setSoTimeout(Math.toIntExact(request.config().workerStartupTimeout().toMillis()));
        try {
            launchInitialWorkers();
            acceptInitialWorkers();
        } catch (Exception exception) {
            close();
            throw exception;
        }
    }

    List<ExecutionResult> execute(List<ScenarioTask> tasks) throws InterruptedException {
        WorkPlan workPlan = WorkPlan.from(tasks);
        SchedulingStrategy scheduler = new FifoSchedulingStrategy();
        scheduler.load(workPlan.representatives());
        ConcurrentLinkedQueue<ExecutionResult> results = new ConcurrentLinkedQueue<>();
        RunProgress progress = new RunProgress(tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(connections.size());
        logger.progress("Scheduler FIFO loaded " + workPlan.units().size() + " work unit(s) for "
                + tasks.size() + " logical task(s); " + connections.size() + " worker(s) ready.");
        for (WorkerConnection connection : List.copyOf(connections)) {
            executor.submit(() -> loop(connection, scheduler, workPlan, results, progress));
        }
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        return List.copyOf(results);
    }

    private void loop(
            WorkerConnection initialConnection,
            SchedulingStrategy scheduler,
            WorkPlan workPlan,
            ConcurrentLinkedQueue<ExecutionResult> results,
            RunProgress progress) {
        WorkerConnection connection = initialConnection;
        String executionLaneId = "local-lane:" + initialConnection.workerId;
        int tasksOnCurrentWorker = 0;
        for (;;) {
            ScenarioTask representative = scheduler.nextEligible(executionLaneId, candidate -> true);
            if (representative == null) {
                stop(connection);
                return;
            }
            WorkUnit unit = workPlan.required(representative.id());
            int attempt = attempts.merge(representative.id(), 1, Integer::sum);
            int busy = progress.busy.addAndGet(unit.tasks().size());
            logger.progress(connection.workerId + " RUN " + unit.label() + " attempt=" + attempt
                    + " leaves=" + unit.tasks().size()
                    + " | completed=" + progress.completed.get() + "/" + progress.total
                    + " busy=" + busy
                    + " queuedUnits=" + scheduler.queued());

            Instant started = Instant.now();
            List<ExecutionResult> unitResults;
            WorkerTelemetry telemetry = null;
            try {
                registrationValidator.requireCanRun(connection.registration, representative.adapterId(), null);
                Envelope run = workAuthority.issueRun(
                        representative.id().value(), connection.workerId, attempt, unit.tasks(), started);
                connection.write(run);
                Envelope response = responseReader.readTerminal(
                        connection.workerId, request.config().workerTaskTimeout(), connection::read);
                if (response == null) {
                    unitResults = failures(unit.tasks(), connection.workerId, attempt, started,
                            "Worker disconnected before returning a work-unit result");
                } else {
                    workAuthority.acceptResult(connection.workerId, response, Instant.now());
                    unitResults = resultValidator.validateBatchOrFailures(
                            unit.tasks(), connection.workerId, attempt, started, response);
                    if (unitResults.stream().noneMatch(this::isProtocolValidationFailure)) {
                        telemetry = response.telemetry();
                    }
                }
            } catch (CapabilityMismatchException exception) {
                unitResults = protocolFailures(unit.tasks(), connection.workerId, attempt, started,
                        "Worker capability mismatch: " + safeMessage(exception));
            } catch (LeaseRegistry.StaleLeaseException exception) {
                unitResults = protocolFailures(unit.tasks(), connection.workerId, attempt, started,
                        "Rejected stale or non-authoritative worker result: " + safeMessage(exception));
            } catch (SocketTimeoutException exception) {
                unitResults = failures(unit.tasks(), connection.workerId, attempt, started,
                        "Worker exceeded work-unit timeout " + request.config().workerTaskTimeout());
            } catch (Exception exception) {
                unitResults = failures(unit.tasks(), connection.workerId, attempt, started, safeMessage(exception));
            }

            progress.busy.addAndGet(-unit.tasks().size());

            if (retryableUnit(unit, unitResults) && attempt <= request.config().infrastructureRetries()) {
                scheduler.requeue(representative);
                ExecutionResult first = unitResults.get(0);
                logger.progress(connection.workerId + " RETRY " + unit.label()
                        + " after " + first.status() + " | nextAttempt=" + (attempt + 1)
                        + " queuedUnits=" + scheduler.queued());
                WorkerConnection replacement = replace(connection, "retryable " + first.status());
                if (replacement == null) return;
                connection = replacement;
                tasksOnCurrentWorker = 0;
                continue;
            }

            attempts.remove(representative.id());
            for (ExecutionResult result : unitResults) {
                results.add(result);
                int completed = progress.completed.incrementAndGet();
                if (!result.buildSuccessful()) progress.failed.incrementAndGet();
                logger.workerCompleted(connection.workerId, result, completed, progress.failed.get(),
                        progress.busy.get(), progress.total);
            }

            if (unitResults.stream().anyMatch(this::requiresWorkerRetirement)) {
                boolean protocolFailure = unitResults.stream().anyMatch(this::isProtocolValidationFailure);
                String reason = protocolFailure ? "protocol result validation failure" : "worker failure";
                if (scheduler.queued() == 0) {
                    retireConnection(connection, reason + " with no queued work remaining");
                    return;
                }
                WorkerConnection replacement = replace(connection, reason);
                if (replacement == null) return;
                connection = replacement;
                tasksOnCurrentWorker = 0;
                continue;
            }

            tasksOnCurrentWorker += unit.tasks().size();
            String recycleReason = recycleReason(tasksOnCurrentWorker, telemetry);
            if (recycleReason != null) {
                if (scheduler.queued() == 0) {
                    logger.progress(connection.workerId + " reached recycle condition (" + recycleReason
                            + ") with no queued work remaining.");
                    stop(connection);
                    return;
                }
                WorkerConnection replacement = replace(connection, recycleReason);
                if (replacement == null) return;
                connection = replacement;
                tasksOnCurrentWorker = 0;
            }
        }
    }

    private boolean retryableUnit(WorkUnit unit, List<ExecutionResult> results) {
        return !unit.scoped()
                && unit.tasks().size() == 1
                && results.size() == 1
                && retryable(results.get(0));
    }

    private boolean retryable(ExecutionResult result) {
        return result.status() == ResultStatus.WORKER_FAILURE
                || result.status() == ResultStatus.INFRASTRUCTURE_FAILURE;
    }

    private boolean requiresWorkerRetirement(ExecutionResult result) {
        return result.status() == ResultStatus.WORKER_FAILURE || isProtocolValidationFailure(result);
    }

    private boolean isProtocolValidationFailure(ExecutionResult result) {
        return ExecutionResultValidator.FAILURE_TYPE.equals(result.failureType());
    }

    private String recycleReason(int tasksOnWorker, WorkerTelemetry telemetry) {
        if (request.config().taskCountRecyclingEnabled()
                && tasksOnWorker >= request.config().maxTasksPerWorker()) {
            return "task-count recycling after " + tasksOnWorker + " task(s)";
        }
        if (request.config().heapRecyclingEnabled() && telemetry != null
                && telemetry.heapUsagePercent() >= request.config().maxHeapUsagePercent()) {
            return "heap recycling at " + telemetry.heapUsagePercent() + "%";
        }
        return null;
    }

    private List<ExecutionResult> failures(
            List<ScenarioTask> tasks, String id, int attempt, Instant started, String message) {
        Instant finished = Instant.now();
        return tasks.stream().map(task -> new ExecutionResult(
                task.id(), task.displayName(), ResultStatus.WORKER_FAILURE,
                Duration.between(started, finished), new WorkerId(id), attempt,
                started, finished, message, "WorkerFailure")).toList();
    }

    private List<ExecutionResult> protocolFailures(
            List<ScenarioTask> tasks, String id, int attempt, Instant started, String message) {
        Instant finished = Instant.now();
        return tasks.stream().map(task -> new ExecutionResult(
                task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                Duration.between(started, finished), new WorkerId(id), attempt,
                started, finished, message, ExecutionResultValidator.FAILURE_TYPE)).toList();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private WorkerConnection replace(WorkerConnection oldConnection, String reason) {
        synchronized (replacementLock) {
            String oldId = oldConnection.workerId;
            retireConnection(oldConnection, reason);
            String replacementId = nextWorkerId();
            try {
                launchWorker(replacementId);
                WorkerConnection replacement = acceptWorker(replacementId);
                connections.add(replacement);
                logger.progress(replacement.workerId + " REPLACED " + oldId + " after " + reason
                        + " and is ready for queued work.");
                return replacement;
            } catch (Exception exception) {
                retireProcess(replacementId);
                logger.progress("Replacement for " + oldId + " failed to start: " + safeMessage(exception)
                        + "; remaining workers will continue.");
                return null;
            }
        }
    }

    private void retireConnection(WorkerConnection connection, String reason) {
        connections.remove(connection);
        try {
            connection.close();
        } catch (Exception ignored) {
        }
        logger.progress(connection.workerId + " RETIRED after " + reason + ".");
        retireProcess(connection.workerId);
    }

    private void retireProcess(String workerId) {
        Process process = processes.remove(workerId);
        if (process != null) destroyProcessTree(process, true);
        outputPumps.remove(workerId);
    }

    private void launchInitialWorkers() throws Exception {
        prepareLogsDirectory();
        for (int index = 0; index < request.config().workerCount(); index++) launchWorker(nextWorkerId());
    }

    private String nextWorkerId() { return "worker-" + workerSequence.incrementAndGet(); }

    private Path logsDirectory() { return dir.resolve("logs"); }

    private void prepareLogsDirectory() throws Exception {
        if (request.config().workerLogFiles()) Files.createDirectories(logsDirectory());
    }

    private void launchWorker(String id) throws Exception {
        prepareLogsDirectory();
        String host = InetAddress.getLoopbackAddress().getHostAddress();
        int port = server.getLocalPort();
        List<String> args = List.of(
                "--host", host,
                "--port", Integer.toString(port),
                "--worker-id", id);
        List<String> command = JavaProcessSupport.command(
                request.runtimeClasspath(), request.effectiveJvmArgs(), request.effectiveSystemProperties(),
                WorkerMain.class.getName(), args);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(request.projectDirectory().toFile())
                .redirectErrorStream(true);
        builder.environment().put("SCENARIOMESH_REMOTE_TOKEN", token);
        Process process = builder.start();
        processes.put(id, process);

        Thread pump = new Thread(
                new WorkerOutputPump(id, process, request.config(), logsDirectory().resolve(id + ".log"), logger),
                "scenariomesh-output-" + id);
        pump.setDaemon(true);
        pump.start();
        outputPumps.put(id, pump);
        logger.progress("Starting " + id + " (pid=" + process.pid() + ")");
    }

    private void acceptInitialWorkers() throws Exception {
        try {
            while (connections.size() < request.config().workerCount()) {
                WorkerConnection connection = acceptWorker(null);
                connections.add(connection);
            }
        } catch (SocketTimeoutException timeout) {
            if (connections.size() < request.config().minimumReadyWorkers()) {
                throw new IllegalStateException("Only " + connections.size() + " of " + request.config().workerCount()
                        + " workers became ready; minimum required is " + request.config().minimumReadyWorkers(), timeout);
            }
            logger.progress("Starting in degraded capacity with " + connections.size() + "/"
                    + request.config().workerCount() + " workers ready (minimumReady="
                    + request.config().minimumReadyWorkers() + ").");
            retireUnconnectedProcesses();
        }
    }

    private void retireUnconnectedProcesses() {
        Set<String> connectedIds = connections.stream()
                .map(connection -> connection.workerId)
                .collect(Collectors.toSet());
        for (String workerId : new ArrayList<>(processes.keySet())) {
            if (!connectedIds.contains(workerId)) retireProcess(workerId);
        }
    }

    private WorkerConnection acceptWorker(String expectedWorkerId) throws Exception {
        for (;;) {
            Socket socket = server.accept();
            WorkerConnection connection = new WorkerConnection(socket);
            Envelope hello = connection.read();
            boolean ownedProcess = hello != null
                    && hello.workerId() != null
                    && processes.containsKey(hello.workerId())
                    && (expectedWorkerId == null || expectedWorkerId.equals(hello.workerId()))
                    && connections.stream().noneMatch(existing -> hello.workerId().equals(existing.workerId));
            if (!ownedProcess) {
                connection.close();
                continue;
            }

            RemoteWorkerRegistration registration;
            try {
                registration = registrationValidator.requireRegistration(hello, token);
            } catch (RuntimeException invalidRegistration) {
                logger.progress("Rejected " + hello.workerId() + " registration: " + safeMessage(invalidRegistration));
                connection.close();
                retireProcess(hello.workerId());
                continue;
            }

            connection.workerId = registration.workerId();
            connection.registration = registration;
            logger.progress(connection.workerId + " READY"
                    + " agent=" + registration.labels().get("agentId")
                    + " slots=" + registration.slots()
                    + " java=" + registration.javaFeature()
                    + " adapters=" + String.join(",", registration.adapterIds()));
            return connection;
        }
    }

    private void stop(WorkerConnection connection) {
        int originalTimeout = 0;
        try {
            originalTimeout = connection.socket.getSoTimeout();
            connection.socket.setSoTimeout(Math.toIntExact(request.config().workerShutdownTimeout().toMillis()));
            connection.write(Envelope.stop(connection.workerId));
            Envelope response = connection.read();
            if (response == null
                    || response.protocolVersion() != Protocol.VERSION
                    || response.type() != Protocol.Type.ACK
                    || !connection.workerId.equals(response.workerId())) {
                logger.progress(connection.workerId + " did not acknowledge STOP; process cleanup will continue.");
            }
        } catch (Exception exception) {
            logger.progress(connection.workerId + " graceful STOP did not complete; process cleanup will continue.");
        } finally {
            try {
                connection.socket.setSoTimeout(originalTimeout);
            } catch (Exception ignored) {
            }
        }
    }

    private void destroyProcessTree(Process process, boolean force) {
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> descendants = root.descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            ProcessHandle descendant = descendants.get(index);
            if (descendant.isAlive()) {
                if (force) descendant.destroyForcibly(); else descendant.destroy();
            }
        }
        if (process.isAlive()) {
            if (force) process.destroyForcibly(); else process.destroy();
        }
        if (force) awaitProcessTreeTermination(root, descendants, request.config().workerShutdownTimeout());
    }

    private void awaitProcessTreeTermination(ProcessHandle root, List<ProcessHandle> descendants, Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        List<ProcessHandle> handles = new ArrayList<>(descendants.size() + 1);
        handles.addAll(descendants);
        handles.add(root);
        for (ProcessHandle handle : handles) {
            if (!handle.isAlive()) continue;
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) return;
            try {
                handle.onExit().get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                return;
            }
        }
    }

    @Override
    public void close() {
        for (WorkerConnection connection : connections) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }

        for (Process process : processes.values()) destroyProcessTree(process, false);

        long deadlineNanos = System.nanoTime() + request.config().workerShutdownTimeout().toNanos();
        for (Process process : processes.values()) {
            if (!process.isAlive()) continue;
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                destroyProcessTree(process, true);
                continue;
            }
            try {
                if (!process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)) destroyProcessTree(process, true);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                destroyProcessTree(process, true);
            }
        }

        long pumpDeadlineNanos = System.nanoTime() + request.config().workerShutdownTimeout().toNanos();
        for (Thread pump : outputPumps.values()) {
            long remainingNanos = pumpDeadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) break;
            try {
                long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                pump.join(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            server.close();
        } catch (Exception ignored) {
        }
    }

    private static final class RunProgress {
        private final int total;
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger busy = new AtomicInteger();

        private RunProgress(int total) { this.total = total; }
    }

    private record WorkUnit(ScenarioTask representative, List<ScenarioTask> tasks, boolean scoped, String label) {
        private WorkUnit {
            tasks = List.copyOf(tasks);
        }
    }

    private record WorkPlan(List<WorkUnit> units, Map<ScenarioId, WorkUnit> byRepresentative) {
        private WorkPlan {
            units = List.copyOf(units);
            byRepresentative = Map.copyOf(byRepresentative);
        }

        static WorkPlan from(List<ScenarioTask> tasks) {
            Map<String, List<ScenarioTask>> grouped = new LinkedHashMap<>();
            Map<String, Boolean> scopedByKey = new LinkedHashMap<>();
            for (ScenarioTask task : tasks) {
                String scope = task.metadata().get(EXECUTION_SCOPE_ID);
                boolean scoped = scope != null && !scope.isBlank();
                String key = scoped ? "scope:" + task.adapterId() + ":" + scope : "leaf:" + task.id().value();
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(task);
                scopedByKey.putIfAbsent(key, scoped);
            }
            List<WorkUnit> units = new ArrayList<>(grouped.size());
            Map<ScenarioId, WorkUnit> byRepresentative = new LinkedHashMap<>();
            for (Map.Entry<String, List<ScenarioTask>> entry : grouped.entrySet()) {
                List<ScenarioTask> members = List.copyOf(entry.getValue());
                ScenarioTask representative = members.get(0);
                boolean scoped = scopedByKey.get(entry.getKey());
                String label = scoped
                        ? representative.framework() + " scope " + members.size() + " leaf/leaves"
                        : representative.displayName();
                WorkUnit unit = new WorkUnit(representative, members, scoped, label);
                units.add(unit);
                byRepresentative.put(representative.id(), unit);
            }
            return new WorkPlan(units, byRepresentative);
        }

        List<ScenarioTask> representatives() {
            return units.stream().map(WorkUnit::representative).toList();
        }

        WorkUnit required(ScenarioId representativeId) {
            WorkUnit unit = byRepresentative.get(representativeId);
            if (unit == null) throw new IllegalStateException("No work unit for representative " + representativeId.value());
            return unit;
        }
    }

    private final class WorkerConnection implements AutoCloseable {
        private final Socket socket;
        private final ProtocolFrameReader reader;
        private final BufferedWriter writer;
        private String workerId;
        private RemoteWorkerRegistration registration;

        private WorkerConnection(Socket socket) throws Exception {
            this.socket = socket;
            this.reader = new ProtocolFrameReader(socket.getInputStream());
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private Envelope read() throws Exception {
            byte[] frame = reader.readBlocking();
            return frame == null ? null : mapper.readValue(frame, Envelope.class);
        }

        private Envelope read(Duration timeout) throws Exception {
            int originalTimeout = socket.getSoTimeout();
            try {
                socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
                return read();
            } finally {
                try {
                    socket.setSoTimeout(originalTimeout);
                } catch (Exception ignored) {
                }
            }
        }

        private void write(Envelope envelope) throws Exception {
            writer.write(mapper.writeValueAsString(envelope));
            writer.newLine();
            writer.flush();
        }

        @Override
        public void close() throws Exception { socket.close(); }
    }
}
