package io.scenariomesh.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.Ports.SchedulingStrategy;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class WorkerPool implements AutoCloseable {
    private final ObjectMapper mapper = JsonCodec.create();
    private final RunRequest request;
    private final Path dir;
    private final String token = UUID.randomUUID().toString();
    private final ServerSocket server;
    private final Map<String, Process> processes = new HashMap<>();
    private final Map<String, Thread> outputPumps = new HashMap<>();
    private final List<WorkerConnection> connections = new ArrayList<>();
    private final RunLogger logger;

    WorkerPool(RunRequest request, Path dir, RunLogger logger) throws Exception {
        this.request = request;
        this.dir = dir;
        this.logger = logger;
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.setSoTimeout(Math.toIntExact(request.config().workerStartupTimeout().toMillis()));
        try {
            launch();
            accept();
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
        for (WorkerConnection connection : connections) {
            executor.submit(() -> loop(connection, scheduler, results, progress));
        }
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        return List.copyOf(results);
    }

    private void loop(WorkerConnection connection,
                      SchedulingStrategy scheduler,
                      ConcurrentLinkedQueue<ExecutionResult> results,
                      RunProgress progress) {
        for (;;) {
            ScenarioTask task = scheduler.nextEligible(candidate -> true);
            if (task == null) {
                stop(connection);
                return;
            }

            int busy = progress.busy.incrementAndGet();
            logger.progress(connection.workerId + " RUN " + task.displayName()
                    + " | completed=" + progress.completed.get() + "/" + progress.total
                    + " busy=" + busy
                    + " queued=" + Math.max(0, progress.total - progress.completed.get() - busy));

            Instant started = Instant.now();
            ExecutionResult result;
            try {
                connection.write(Envelope.run(connection.workerId, task));
                Envelope response = connection.read();
                if (response == null) {
                    result = failure(task, connection.workerId, started, "Worker disconnected before returning a result");
                } else if (response.type() == Protocol.Type.RESULT && response.result() != null) {
                    result = response.result();
                } else {
                    result = failure(task, connection.workerId, started,
                            response.error() == null ? "Unexpected worker response: " + response.type() : response.error());
                }
            } catch (Exception exception) {
                result = failure(task, connection.workerId, started, exception.getMessage());
            }

            results.add(result);
            progress.busy.decrementAndGet();
            int completed = progress.completed.incrementAndGet();
            if (!result.passed()) {
                progress.failed.incrementAndGet();
            }
            logger.workerCompleted(connection.workerId, result, completed, progress.failed.get(),
                    progress.busy.get(), progress.total);

            if (result.status() == ResultStatus.WORKER_FAILURE) {
                return;
            }
        }
    }

    private ExecutionResult failure(ScenarioTask task, String id, Instant started, String message) {
        Instant finished = Instant.now();
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.WORKER_FAILURE,
                Duration.between(started, finished), new WorkerId(id), 1, started, finished,
                message, "WorkerFailure");
    }

    private void launch() throws Exception {
        Path logs = dir.resolve("logs");
        if (request.config().workerLogFiles()) {
            Files.createDirectories(logs);
        }
        String host = InetAddress.getLoopbackAddress().getHostAddress();
        int port = server.getLocalPort();
        for (int index = 1; index <= request.config().workerCount(); index++) {
            String id = "worker-" + index;
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
                    new WorkerOutputPump(id, process, request.config(), logs.resolve(id + ".log"), logger),
                    "scenariomesh-output-" + id);
            pump.setDaemon(true);
            pump.start();
            outputPumps.put(id, pump);
            logger.progress("Starting " + id + " (pid=" + process.pid() + ")");
        }
    }

    private void accept() throws Exception {
        while (connections.size() < request.config().workerCount()) {
            Socket socket = server.accept();
            WorkerConnection connection = new WorkerConnection(socket);
            Envelope hello = connection.read();
            if (hello == null
                    || hello.protocolVersion() != Protocol.VERSION
                    || hello.type() != Protocol.Type.HELLO
                    || !token.equals(hello.token())
                    || !processes.containsKey(hello.workerId())) {
                connection.close();
                continue;
            }
            connection.workerId = hello.workerId();
            connections.add(connection);
            logger.progress(connection.workerId + " READY");
        }
    }

    private void stop(WorkerConnection connection) {
        try {
            connection.write(Envelope.stop(connection.workerId));
            connection.read();
        } catch (Exception ignored) {
            // Process cleanup in close() is the final safety net.
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
        for (Process process : processes.values()) {
            if (!process.isAlive()) {
                continue;
            }
            process.destroy();
            try {
                if (!process.waitFor(request.config().workerShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        for (Thread pump : outputPumps.values()) {
            try {
                pump.join(request.config().workerShutdownTimeout().toMillis());
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
