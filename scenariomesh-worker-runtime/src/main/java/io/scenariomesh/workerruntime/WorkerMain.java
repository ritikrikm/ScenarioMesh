package io.scenariomesh.workerruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.WorkerTaskCleanup;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerTelemetry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

public final class WorkerMain {
    private WorkerMain() {}

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        ObjectMapper mapper = JsonCodec.create();
        AdapterRegistry adapters = new AdapterRegistry();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        List<WorkerTaskCleanup> cleanupHooks = ServiceLoader.load(WorkerTaskCleanup.class, classLoader)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        Map<String, String> properties = new HashMap<>();
        System.getProperties().forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));

        try (Socket socket = new Socket(InetAddress.getByName(parsed.host), parsed.port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            write(mapper, writer, Envelope.hello(parsed.workerId, parsed.token));
            for (String line; (line = reader.readLine()) != null;) {
                Envelope envelope = mapper.readValue(line, Envelope.class);
                validate(envelope);
                if (envelope.type() == Protocol.Type.STOP) {
                    write(mapper, writer, Envelope.ack(parsed.workerId));
                    return;
                }
                if (envelope.type() != Protocol.Type.RUN || envelope.task() == null || envelope.attempt() == null || envelope.attempt() < 1) {
                    write(mapper, writer, Envelope.error(parsed.workerId, "Expected RUN command with a positive attempt"));
                    continue;
                }

                ScenarioTask task = envelope.task();
                ExecutionContext context = new ExecutionContext(
                        classLoader,
                        new WorkerId(parsed.workerId),
                        envelope.attempt(),
                        properties);
                ExecutionResult result = execute(adapters, task, context);
                result = runCleanupHooks(cleanupHooks, task, context, result);
                write(mapper, writer, Envelope.result(parsed.workerId, result, telemetry()));
            }
        }
    }

    private static ExecutionResult execute(AdapterRegistry adapters, ScenarioTask task, ExecutionContext context) {
        try {
            return Objects.requireNonNull(
                    adapters.required(task.adapterId()).execute(task, context),
                    "Adapter returned null result");
        } catch (Exception exception) {
            Instant now = Instant.now();
            return new ExecutionResult(
                    task.id(),
                    task.displayName(),
                    ResultStatus.INFRASTRUCTURE_FAILURE,
                    Duration.ZERO,
                    context.workerId(),
                    context.attempt(),
                    now,
                    now,
                    safeMessage(exception),
                    exception.getClass().getName());
        }
    }

    private static ExecutionResult runCleanupHooks(
            List<WorkerTaskCleanup> hooks,
            ScenarioTask task,
            ExecutionContext context,
            ExecutionResult result) {
        for (WorkerTaskCleanup hook : hooks) {
            try {
                hook.afterTask(task, context, result);
            } catch (Exception exception) {
                Instant finished = Instant.now();
                return new ExecutionResult(
                        task.id(),
                        task.displayName(),
                        ResultStatus.INFRASTRUCTURE_FAILURE,
                        Duration.between(result.startedAt(), finished),
                        context.workerId(),
                        context.attempt(),
                        result.startedAt(),
                        finished,
                        "Worker cleanup hook " + hook.getClass().getName() + " failed: " + safeMessage(exception),
                        exception.getClass().getName());
            }
        }
        return result;
    }

    private static WorkerTelemetry telemetry() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new WorkerTelemetry(heap.getUsed(), heap.getMax());
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static void validate(Envelope envelope) {
        if (envelope.protocolVersion() != Protocol.VERSION) {
            throw new IllegalArgumentException("Unsupported ScenarioMesh protocol version: " + envelope.protocolVersion());
        }
    }

    private static void write(ObjectMapper mapper, BufferedWriter writer, Envelope envelope) throws Exception {
        writer.write(mapper.writeValueAsString(envelope));
        writer.newLine();
        writer.flush();
    }

    private record Arguments(String host, int port, String token, String workerId) {
        private static Arguments parse(String[] args) {
            String host = null;
            String token = null;
            String workerId = null;
            Integer port = null;
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(key + " requires a value");
                }
                String value = args[++i];
                switch (key) {
                    case "--host" -> host = value;
                    case "--port" -> port = Integer.parseInt(value);
                    case "--token" -> token = value;
                    case "--worker-id" -> workerId = value;
                    default -> throw new IllegalArgumentException("Unknown worker argument: " + key);
                }
            }
            if (host == null || port == null || token == null || workerId == null) {
                throw new IllegalArgumentException("--host, --port, --token and --worker-id are required");
            }
            return new Arguments(host, port, token, workerId);
        }
    }
}
