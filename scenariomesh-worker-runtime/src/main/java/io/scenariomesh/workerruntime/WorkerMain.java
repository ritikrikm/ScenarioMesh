package io.scenariomesh.workerruntime;

import io.scenariomesh.controljson.ControlJsonCodec;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.WorkUnitExecution;
import io.scenariomesh.core.Ports.WorkerTaskCleanup;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import io.scenariomesh.protocol.Protocol.WorkerCapabilities;
import io.scenariomesh.protocol.Protocol.WorkerTelemetry;
import io.scenariomesh.protocol.ProtocolFrameReader;
import org.junit.platform.engine.TestEngine;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

public final class WorkerMain {
    private static final String TRACE_PREFIX = "[ScenarioMesh][WORKER TRACE]";

    private WorkerMain() {}

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        Map<String, String> environment = System.getenv();
        String token = RemoteWorkerTransport.authenticationToken(environment);
        AdapterRegistry adapters = new AdapterRegistry();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        List<WorkerTaskCleanup> cleanupHooks = ServiceLoader.load(WorkerTaskCleanup.class, classLoader)
                .stream().map(ServiceLoader.Provider::get).toList();
        Map<String, String> properties = new HashMap<>();
        System.getProperties().forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
        WorkerCapabilities capabilities = capabilities(adapters, classLoader);

        trace("START worker=" + parsed.workerId + " target=" + parsed.host + ":" + parsed.port
                + " bootstrapProtocol=" + Protocol.BOOTSTRAP_VERSION
                + " supportedRange=[" + Protocol.MIN_SUPPORTED_VERSION + "," + Protocol.VERSION + "]"
                + " java=" + capabilities.javaFeature()
                + " slots=" + capabilities.slots()
                + " adapters=" + capabilities.adapterIds()
                + " engines=" + capabilities.engineIds());

        try (Socket socket = RemoteWorkerTransport.connect(parsed.host, parsed.port, environment);
             ProtocolFrameReader reader = new ProtocolFrameReader(socket.getInputStream());
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            trace("CONNECTED worker=" + parsed.workerId + " local=" + socket.getLocalSocketAddress()
                    + " remote=" + socket.getRemoteSocketAddress());
            Envelope hello = Envelope.hello(parsed.workerId, token, capabilities);
            trace("HELLO_SEND worker=" + parsed.workerId + " bootstrapProtocol=" + Protocol.BOOTSTRAP_VERSION
                    + " advertisedRange=[" + capabilities.minProtocolVersion() + "," + capabilities.maxProtocolVersion() + "]");
            write(writer, hello, Protocol.BOOTSTRAP_VERSION);

            trace("FIRST_COMMAND_WAIT worker=" + parsed.workerId);
            byte[] firstFrame = reader.readBlocking();
            if (firstFrame == null) {
                trace("FIRST_COMMAND_EOF worker=" + parsed.workerId);
                return;
            }
            Envelope first = ControlJsonCodec.read(firstFrame, Envelope.class);
            traceEnvelope("IN_FIRST", parsed.workerId, first);
            int sessionProtocol = requireSupportedProtocol(first.protocolVersion());
            boolean negotiationAck = first.type() == Protocol.Type.ACK;
            trace("SESSION_LOCK worker=" + parsed.workerId + " protocol=" + sessionProtocol
                    + " source=" + (negotiationAck ? "negotiation-ack" : "legacy-first-command"));

            try (PresenceHeartbeatEmitter presence = PresenceHeartbeatEmitter.start(
                    parsed.workerId, WorkerMain::telemetry,
                    heartbeat -> write(writer, heartbeat, sessionProtocol))) {
                trace("PRESENCE_STARTED worker=" + parsed.workerId + " protocol=" + sessionProtocol);
                boolean draining = false;
                Envelope envelope = negotiationAck ? null : first;
                while (true) {
                    if (envelope == null) {
                        byte[] frame = reader.readBlocking();
                        if (frame == null) {
                            trace("COMMAND_EOF worker=" + parsed.workerId + " protocol=" + sessionProtocol);
                            break;
                        }
                        envelope = ControlJsonCodec.read(frame, Envelope.class);
                        traceEnvelope("IN", parsed.workerId, envelope);
                    }
                    validate(envelope, sessionProtocol);
                    if (envelope.type() == Protocol.Type.ACK) {
                        trace("UNEXPECTED_ACK worker=" + parsed.workerId + " protocol=" + sessionProtocol);
                        throw new IllegalArgumentException("Unexpected protocol ACK after session negotiation");
                    }
                    if (envelope.type() == Protocol.Type.DRAIN) {
                        draining = true;
                        trace("DRAIN_ACCEPT worker=" + parsed.workerId + " protocol=" + sessionProtocol);
                        write(writer, Envelope.ack(parsed.workerId), sessionProtocol);
                        envelope = null;
                        continue;
                    }
                    if (envelope.type() == Protocol.Type.STOP) {
                        trace("STOP_ACCEPT worker=" + parsed.workerId + " protocol=" + sessionProtocol);
                        write(writer, Envelope.ack(parsed.workerId), sessionProtocol);
                        trace("STOPPED worker=" + parsed.workerId);
                        return;
                    }
                    if (draining && envelope.type() == Protocol.Type.RUN) {
                        trace("RUN_REJECT_DRAINING worker=" + parsed.workerId + " workUnit=" + value(envelope.workUnitId()));
                        write(writer, Envelope.error(parsed.workerId,
                                "Worker is draining and will not accept new work"), sessionProtocol);
                        envelope = null;
                        continue;
                    }
                    if (envelope.type() != Protocol.Type.RUN || envelope.tasks().isEmpty()
                            || envelope.attempt() == null || envelope.attempt() < 1) {
                        trace("RUN_REJECT_INVALID worker=" + parsed.workerId + " type=" + envelope.type()
                                + " attempt=" + envelope.attempt()
                                + " tasks=" + (envelope.tasks() == null ? "null" : envelope.tasks().size()));
                        write(writer, Envelope.error(parsed.workerId,
                                "Expected RUN command with at least one task and a positive attempt"), sessionProtocol);
                        envelope = null;
                        continue;
                    }

                    List<ScenarioTask> dispatched = envelope.tasks();
                    trace("RUN_START worker=" + parsed.workerId
                            + " workUnit=" + value(envelope.workUnitId())
                            + " lease=" + value(envelope.leaseId())
                            + " attempt=" + envelope.attempt()
                            + " tasks=" + dispatched.size()
                            + " adapter=" + dispatched.get(0).adapterId());
                    ExecutionContext context = new ExecutionContext(classLoader, new WorkerId(parsed.workerId),
                            envelope.attempt(), properties);
                    WorkUnitExecution execution;
                    List<ExecutionResult> cleaned;
                    try (LeaseHeartbeatEmitter heartbeat = LeaseHeartbeatEmitter.start(
                            parsed.workerId, envelope, WorkerMain::telemetry,
                            heartbeatEnvelope -> write(writer, heartbeatEnvelope, sessionProtocol))) {
                        trace("LEASE_HEARTBEAT_STARTED worker=" + parsed.workerId
                                + " workUnit=" + value(envelope.workUnitId()) + " lease=" + value(envelope.leaseId()));
                        execution = executeWorkUnit(adapters, dispatched, context);
                        trace("ADAPTER_EXECUTION_DONE worker=" + parsed.workerId
                                + " workUnit=" + value(envelope.workUnitId())
                                + " materializedTasks=" + execution.tasks().size()
                                + " rawResults=" + execution.results().size());
                        cleaned = runCleanupHooks(cleanupHooks, execution.tasks(), context, execution.results());
                        heartbeat.throwIfFailed();
                    }
                    long failed = cleaned.stream().filter(result -> !result.buildSuccessful()).count();
                    trace("RESULT_BATCH_READY worker=" + parsed.workerId
                            + " workUnit=" + value(envelope.workUnitId())
                            + " lease=" + value(envelope.leaseId())
                            + " results=" + cleaned.size() + " unsuccessful=" + failed);
                    write(writer, Envelope.resultBatch(parsed.workerId,
                            envelope.workUnitId(), envelope.leaseId(), execution.tasks(), cleaned, telemetry()),
                            sessionProtocol);
                    envelope = null;
                }
            }
        } catch (Exception exception) {
            trace("FATAL worker=" + parsed.workerId + " exception=" + exception.getClass().getName()
                    + " message=" + safeMessage(exception));
            exception.printStackTrace(System.err);
            throw exception;
        } finally {
            trace("EXIT worker=" + parsed.workerId);
        }
    }

    static WorkerCapabilities capabilities(AdapterRegistry adapters, ClassLoader classLoader) throws Exception {
        String configuredAgent = System.getenv("JENKINS_NODE_NAME");
        String agentId = configuredAgent == null || configuredAgent.isBlank()
                ? InetAddress.getLocalHost().getHostName() : configuredAgent.trim();
        String os = System.getProperty("os.name", "unknown");
        String architecture = System.getProperty("os.arch", "unknown");
        int javaFeature = Runtime.version().feature();
        Set<String> adapterIds = adapters.available(classLoader).stream()
                .map(adapter -> adapter.id()).collect(Collectors.toUnmodifiableSet());
        Set<String> engineIds = ServiceLoader.load(TestEngine.class, classLoader).stream()
                .map(ServiceLoader.Provider::get).map(TestEngine::getId)
                .filter(id -> id != null && !id.isBlank()).collect(Collectors.toUnmodifiableSet());
        String fingerprintInput = String.join("\n",
                Integer.toString(Protocol.VERSION), Integer.toString(javaFeature),
                System.getProperty("java.vendor", "unknown"), System.getProperty("java.version", "unknown"),
                os, architecture, System.getProperty("java.class.path", ""));
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(fingerprintInput.getBytes(StandardCharsets.UTF_8));
        return new WorkerCapabilities(agentId, 1, javaFeature, os, architecture,
                HexFormat.of().formatHex(digest), adapterIds, engineIds,
                Protocol.MIN_SUPPORTED_VERSION, Protocol.VERSION);
    }

    private static WorkUnitExecution executeWorkUnit(AdapterRegistry adapters, List<ScenarioTask> tasks,
                                                     ExecutionContext context) {
        String adapterId = tasks.get(0).adapterId();
        for (ScenarioTask task : tasks) {
            if (!adapterId.equals(task.adapterId())) {
                return new WorkUnitExecution(tasks, failures(tasks, context,
                        "Worker received a work unit containing multiple adapters", "MixedAdapterWorkUnit"));
            }
        }
        try {
            return Objects.requireNonNull(adapters.required(adapterId).executeWorkUnit(tasks, context),
                    "Adapter returned null work-unit execution");
        } catch (Exception exception) {
            trace("ADAPTER_EXECUTION_FAILURE worker=" + context.workerId().value()
                    + " adapter=" + adapterId + " exception=" + exception.getClass().getName()
                    + " message=" + safeMessage(exception));
            return new WorkUnitExecution(tasks, failures(tasks, context,
                    safeMessage(exception), exception.getClass().getName()));
        }
    }

    private static List<ExecutionResult> runCleanupHooks(List<WorkerTaskCleanup> hooks, List<ScenarioTask> tasks,
                                                         ExecutionContext context, List<ExecutionResult> results) {
        Map<String, ExecutionResult> byId = new HashMap<>();
        for (ExecutionResult result : results) byId.put(result.scenarioId().value(), result);
        List<ExecutionResult> cleaned = new ArrayList<>(tasks.size());
        for (ScenarioTask task : tasks) {
            ExecutionResult result = byId.get(task.id().value());
            if (result == null) result = failure(task, context,
                    "Adapter did not return a result for this materialized task", "MissingBatchResult");
            cleaned.add(runCleanupHooks(hooks, task, context, result));
        }
        return List.copyOf(cleaned);
    }

    static ExecutionResult runCleanupHooks(List<WorkerTaskCleanup> hooks, ScenarioTask task,
                                           ExecutionContext context, ExecutionResult result) {
        for (WorkerTaskCleanup hook : hooks) {
            try { hook.afterTask(task, context, result); }
            catch (Exception exception) {
                trace("CLEANUP_FAILURE worker=" + context.workerId().value()
                        + " task=" + task.id().value()
                        + " hook=" + hook.getClass().getName()
                        + " exception=" + exception.getClass().getName()
                        + " message=" + safeMessage(exception));
                Instant finished = Instant.now();
                return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                        Duration.between(result.startedAt(), finished), context.workerId(), context.attempt(),
                        result.startedAt(), finished, cleanupFailureMessage(hook, exception, result),
                        "CleanupFailure:" + exception.getClass().getName());
            }
        }
        return result;
    }

    private static List<ExecutionResult> failures(List<ScenarioTask> tasks, ExecutionContext context,
                                                  String message, String type) {
        return tasks.stream().map(task -> failure(task, context, message, type)).toList();
    }

    private static ExecutionResult failure(ScenarioTask task, ExecutionContext context, String message, String type) {
        Instant now = Instant.now();
        return new ExecutionResult(task.id(), task.displayName(), ResultStatus.INFRASTRUCTURE_FAILURE,
                Duration.ZERO, context.workerId(), context.attempt(), now, now, message, type);
    }

    private static String cleanupFailureMessage(WorkerTaskCleanup hook, Exception cleanupFailure,
                                                ExecutionResult originalResult) {
        StringBuilder message = new StringBuilder().append("Worker cleanup hook ")
                .append(hook.getClass().getName()).append(" failed: ").append(safeMessage(cleanupFailure))
                .append(". Original task outcome: status=").append(originalResult.status());
        if (originalResult.failureType() != null && !originalResult.failureType().isBlank()) {
            message.append(", failureType=").append(originalResult.failureType());
        }
        if (originalResult.failureMessage() != null && !originalResult.failureMessage().isBlank()) {
            message.append(", failureMessage=").append(originalResult.failureMessage());
        }
        return message.toString();
    }

    private static WorkerTelemetry telemetry() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new WorkerTelemetry(heap.getUsed(), heap.getMax());
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static int requireSupportedProtocol(int version) {
        if (version < Protocol.MIN_SUPPORTED_VERSION || version > Protocol.VERSION) {
            trace("UNSUPPORTED_PROTOCOL version=" + version + " supported=[" + Protocol.MIN_SUPPORTED_VERSION
                    + "," + Protocol.VERSION + "]");
            throw new IllegalArgumentException("Unsupported ScenarioMesh protocol version: " + version);
        }
        return version;
    }

    private static void validate(Envelope envelope, int sessionProtocol) {
        if (envelope.protocolVersion() != sessionProtocol) {
            trace("PROTOCOL_MISMATCH expected=" + sessionProtocol + " actual=" + envelope.protocolVersion()
                    + " type=" + envelope.type() + " worker=" + envelope.workerId());
            throw new IllegalArgumentException("ScenarioMesh coordinator changed negotiated protocol version from "
                    + sessionProtocol + " to " + envelope.protocolVersion());
        }
    }

    private static void write(BufferedWriter writer, Envelope envelope, int protocolVersion) throws Exception {
        Envelope versioned = envelope.withProtocolVersion(protocolVersion);
        traceEnvelope("OUT", versioned.workerId(), versioned);
        synchronized (writer) {
            writer.write(ControlJsonCodec.write(versioned));
            writer.newLine();
            writer.flush();
        }
    }

    private static void traceEnvelope(String direction, String workerId, Envelope envelope) {
        trace(direction + " worker=" + workerId
                + " type=" + envelope.type()
                + " protocol=" + envelope.protocolVersion()
                + " workUnit=" + value(envelope.workUnitId())
                + " lease=" + value(envelope.leaseId())
                + " attempt=" + envelope.attempt()
                + " tasks=" + (envelope.tasks() == null ? "null" : envelope.tasks().size())
                + " results=" + (envelope.results() == null ? "null" : envelope.results().size()));
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static void trace(String message) {
        System.err.println(TRACE_PREFIX + " " + Instant.now() + " thread=" + Thread.currentThread().getName() + " " + message);
    }

    private record Arguments(String host, int port, String workerId) {
        private static Arguments parse(String[] args) {
            String host = null, workerId = null;
            Integer port = null;
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if (i + 1 >= args.length) throw new IllegalArgumentException(key + " requires a value");
                String value = args[++i];
                switch (key) {
                    case "--host" -> host = value;
                    case "--port" -> port = Integer.parseInt(value);
                    case "--worker-id" -> workerId = value;
                    default -> throw new IllegalArgumentException("Unknown worker argument: " + key);
                }
            }
            if (host == null || port == null || workerId == null) {
                throw new IllegalArgumentException("--host, --port and --worker-id are required; authentication is provided through environment variables");
            }
            return new Arguments(host, port, workerId);
        }
    }
}
