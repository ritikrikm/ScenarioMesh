package io.scenariomesh.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.scenariomesh.workerruntime.JsonCodec;
import io.scenariomesh.workerruntime.WorkerMain;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
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
    private final ObjectMapper mapper = JsonCodec.create();
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

    WorkerPool(RunRequest request, Path dir, RunLogger logger) throws Exception {
        this.request = request;
        this.dir = dir;
        this.logger = logger;
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
        SchedulingStrategy scheduler = new FifoSchedulingStrategy();
        scheduler.load(tasks);
        ConcurrentLinkedQueue<ExecutionResult> results = new ConcurrentLinkedQueue<>();
        RunProgress progress = new RunProgress(tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(connections.size());
        logger.progress("Scheduler FIFO loaded " + tasks.size() + " task(s); " + connections.size() + " worker(s) ready.");
        for (WorkerConnection connection : List.copyOf(connections)) {
            executor.submit(() -> loop(connection, scheduler, results, progress));
        }
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        return List.copyOf(results);
    }

    private void loop(WorkerConnection initialConnection,
                      SchedulingStrategy scheduler,
                      ConcurrentLinkedQueue<ExecutionResult> results,
                      RunProgress progress) {
        WorkerConnection connection = initialConnection;
        int tasksOnCurrentWorker = 0;
        for (;;) {
            ScenarioTask task = scheduler.nextEligible(candidate -> true);
            if (task == null) {
                stop(connection);
                return;
            }

            int attempt = attempts.merge(task.id(), 1, Integer::sum);
            int busy = progress.busy.incrementAndGet();
            logger.progress(connection.workerId + " RUN " + task.displayName() + " attempt=" + attempt
                    + " | completed=" + progress.completed.get() + "/" + progress.total
                    + " busy=" + busy
                    + " queued=" + scheduler.queued());

            Instant started = Instant.now();
            ExecutionResult result;
            WorkerTelemetry telemetry = null;
            try {
                connection.write(Envelope.run(connection.workerId, task, attempt));
                Envelope response = connection.read(request.config().workerTaskTimeout());
                if (response == null) {
                    result = failure(task, connection.workerId, attempt, started,
                            "Worker disconnected before returning a result");
                } else if (response.type() == Protocol.Type.RESULT && response.result() != null) {
                    result = response.result();
                    telemetry = response.telemetry();
                } else {
                    result = failure(task, connection.workerId, attempt, started,
                            response.error() == null ? "Unexpected worker response: " + response.type() : response.error());
                }
            } catch (SocketTimeoutException exception) {
                result = failure(task, connection.workerId, attempt, started,
                        "Worker exceeded task timeout " + request.config().workerTaskTimeout());
            } catch (Exception exception) {
                result = failure(task, connection.workerId, attempt, started, safeMessage(exception));
            }

            progress.busy.decrementAndGet();

            if (retryable(result) && attempt <= request.config().infrastructureRetries()) {
                scheduler.requeue(task);
                logger.progress(connection.workerId + " RETRY " + task.displayName()
                        + " after " + result.status() + " | nextAttempt=" + (attempt + 1)
                        + " queued=" + scheduler.queued());
                WorkerConnection replacement = replace(connection, "retryable " + result.status());
                if (replacement == null) {
                    return;
                }
                connection = replacement;
                tasksOnCurrentWorker = 0;
                continue;
            }

            attempts.remove(task.id());
            results.add(result);
            int completed = progress.completed.incrementAndGet();
            if (!result.passed()) {
                progress.failed.incrementAndGet();
            }
            logger.workerCompleted(connection.workerId, result, completed, progress.failed.get(),
                    progress.busy.get(), progress.total);

            if (result.status() == ResultStatus.WORKER_FAILURE) {
                if (scheduler.queued() == 0) {
                    retireConnection(connection, "worker failure with no queued work remaining");
                    return;
                }
                WorkerConnection replacement = replace(connection, "worker failure");
                if (replacement == null) {
                    return;
                }
                connection = replacement;
                tasksOnCurrentWorker = 0;
                continue;
            }

            tasksOnCurrentWorker++;
            String recycleReason = recycleReason(tasksOnCurrentWorker, telemetry);
            if (recycleReason != null) {
                if (scheduler.queued() == 0) {
                    logger.progress(connection.workerId + " reached recycle condition (" + recycleReason
                            + ") with no queued work remaining.");
                    stop(connection);
                    return;
                }
                WorkerConnection replacement = replace(connection, recycleReason);
                if (replacement == null) {
                    return;
                }
                connection = replacement;
                tasksOnCurrentWorker = 0;
            }
        }
    }

    private boolean retryable(ExecutionResult result) {
        return result.status() == ResultStatus.WORKER_FAILURE
                || result.status() == ResultStatus.INFRASTRUCTURE_FAILURE;
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

    private ExecutionResult failure(ScenarioTask task, String id, int attempt, Instant started, String message) {
        Instant finished = Instant.now();
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.WORKER_FAILURE,
                Duration.between(started, finished), new WorkerId(id), attempt, started, finished,
                message, "WorkerFailure");
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
            // A failed worker connection may already be closed.
        }
        logger.progress(connection.workerId + " RETIRED after " + reason + ".");
        retireProcess(connection.workerId);
    }

    private void retireProcess(String workerId) {
        Process process = processes.remove(workerId);
        if (process != null) {
            destroyProcessTree(process, true);
        }
        outputPumps.remove(workerId);
    }

    private void launchInitialWorkers() throws Exception {
        prepareLogsDirectory();
        for (int index = 0; index < request.config().workerCount(); index++) {
            launchWorker(nextWorkerId());
        }
    }

    private String nextWorkerId() {
        return "worker-" + workerSequence.incrementAndGet();
    }

    private Path logsDirectory() {
        return dir.resolve("logs");
    }

    private void prepareLogsDirectory() throws Exception {
        if (request.config().workerLogFiles()) {
            Files.createDirectories(logsDirectory());
        }
    }

    private void launchWorker(String id) throws Exception {
        prepareLogsDirectory();
        String host = InetAddress.getLoopbackAddress().getHostAddress();
        int port = server.getLocalPort();
        List<String> args = List.of(
                "--host", host,
                "--port", Integer.toString(port),
                "--token", token,
                "--worker-id", id);
        List<String> command = JavaProcessSupport.command(
                request.runtimeClasspath(),
                request.effectiveJvmArgs(),
                request.effectiveSystemProperties(),
                WorkerMain.class.getName(),
                args);
        Process process = new ProcessBuilder(command)
                .directory(request.projectDirectory().toFile())
                .redirectErrorStream(true)
                .start();
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
            if (!connectedIds.contains(workerId)) {
                retireProcess(workerId);
            }
        }
    }

    private WorkerConnection acceptWorker(String expectedWorkerId) throws Exception {
        for (;;) {
            Socket socket = server.accept();
            WorkerConnection connection = new WorkerConnection(socket);
            Envelope hello = connection.read();
            boolean valid = hello != null
                    && hello.protocolVersion() == Protocol.VERSION
                    && hello.type() == Protocol.Type.HELLO
                    && token.equals(hello.token())
                    && processes.containsKey(hello.workerId())
                    && (expectedWorkerId == null || expectedWorkerId.equals(hello.workerId()))
                    && connections.stream().noneMatch(existing -> hello.workerId().equals(existing.workerId));
            if (!valid) {
                connection.close();
                continue;
            }
            connection.workerId = hello.workerId();
            logger.progress(connection.workerId + " READY");
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
            if (response == null || response.type() != Protocol.Type.ACK) {
                logger.progress(connection.workerId + " did not acknowledge STOP; process cleanup will continue.");
            }
        } catch (Exception exception) {
            logger.progress(connection.workerId + " graceful STOP did not complete; process cleanup will continue.");
        } finally {
            try {
                connection.socket.setSoTimeout(originalTimeout);
            } catch (Exception ignored) {
                // Socket may already be closed.
            }
        }
    }

    private void destroyProcessTree(Process process, boolean force) {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            ProcessHandle descendant = descendants.get(index);
            if (descendant.isAlive()) {
                if (force) {
                    descendant.destroyForcibly();
                } else {
                    descendant.destroy();
                }
            }
        }
        if (process.isAlive()) {
            if (force) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        }
    }

    @Override
    public void close() {
        for (WorkerConnection connection : connections) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // Continue closing remaining workers.
            }
        }

        // Signal every worker process tree first so the configured shutdown timeout applies to the pool as a whole.
        for (Process process : processes.values()) {
            destroyProcessTree(process, false);
        }

        long deadlineNanos = System.nanoTime() + request.config().workerShutdownTimeout().toNanos();
        for (Process process : processes.values()) {
            if (!process.isAlive()) {
                continue;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                destroyProcessTree(process, true);
                continue;
            }
            try {
                if (!process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)) {
                    destroyProcessTree(process, true);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                destroyProcessTree(process, true);
            }
        }

        long pumpDeadlineNanos = System.nanoTime() + request.config().workerShutdownTimeout().toNanos();
        for (Thread pump : outputPumps.values()) {
            long remainingNanos = pumpDeadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
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
            // Nothing else to clean up.
        }
    }

    private static final class RunProgress {
        private final int total;
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger busy = new AtomicInteger();

        private RunProgress(int total) {
            this.total = total;
        }
    }

    private final class WorkerConnection implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;
        private String workerId;

        private WorkerConnection(Socket socket) throws Exception {
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private Envelope read() throws Exception {
            String line = reader.readLine();
            return line == null ? null : mapper.readValue(line, Envelope.class);
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
                    // Worker may have disconnected while the task was running.
                }
            }
        }

        private void write(Envelope envelope) throws Exception {
            writer.write(mapper.writeValueAsString(envelope));
            writer.newLine();
            writer.flush();
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
