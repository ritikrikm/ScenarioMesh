package io.scenariomesh.workerruntime;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.WorkUnitExecution;
import io.scenariomesh.core.Ports.WorkerTaskCleanup;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * One-shot class-scoped target process used for Maven {@code reuseForks=false}.
 *
 * <p>No ScenarioMesh socket protocol, heartbeat, coordinator, or JSON stack is initialized here.
 * Those stay in the supervisor JVM so Maven target heap limits apply to the test process rather
 * than to the distributed control plane.</p>
 */
public final class ForkedWorkUnitMain {
    private ForkedWorkUnitMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("request and response paths are required");
        Path requestFile = Path.of(args[0]);
        Path responseFile = Path.of(args[1]);
        Request request = readRequest(requestFile);

        String encodedClasspath = System.getProperty(TargetClasspathDescriptor.SYSTEM_PROPERTY);
        List<Path> targetClasspath = TargetClasspathDescriptor.decodeInline(encodedClasspath);
        Thread thread = Thread.currentThread();
        ClassLoader controlLoader = ForkedWorkUnitMain.class.getClassLoader();
        try (TargetRuntimeClassLoader targetLoader =
                     TargetRuntimeClassLoader.fromClasspath(targetClasspath, controlLoader)) {
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(targetLoader);
            try {
                AdapterRegistry adapters = new AdapterRegistry(targetLoader);
                List<WorkerTaskCleanup> cleanupHooks = ServiceLoader.load(WorkerTaskCleanup.class, targetLoader)
                        .stream().map(ServiceLoader.Provider::get).toList();
                Map<String, String> properties = new HashMap<>();
                System.getProperties().forEach((key, value) ->
                        properties.put(String.valueOf(key), String.valueOf(value)));
                properties.remove(TargetClasspathDescriptor.SYSTEM_PROPERTY);
                ExecutionContext context = new ExecutionContext(
                        targetLoader, new WorkerId(request.workerId()), request.attempt(), properties);
                WorkUnitExecution execution = WorkUnitRuntime.execute(adapters, request.tasks(), context);
                List<ExecutionResult> cleaned = WorkUnitRuntime.cleanup(
                        cleanupHooks, execution.tasks(), context, execution.results());
                writeResponse(responseFile, new Response(execution.tasks(), cleaned));
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    static void writeRequest(Path file, Request request) throws Exception {
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(file))) {
            output.writeObject(request);
        }
    }

    static Response readResponse(Path file) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) {
            Object value = input.readObject();
            if (!(value instanceof Response response)) throw new IllegalArgumentException("invalid forked work response");
            return response;
        }
    }

    private static Request readRequest(Path file) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) {
            Object value = input.readObject();
            if (!(value instanceof Request request)) throw new IllegalArgumentException("invalid forked work request");
            return request;
        }
    }

    private static void writeResponse(Path file, Response response) throws Exception {
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(file))) {
            output.writeObject(response);
        }
    }

    record Request(List<ScenarioTask> tasks, String workerId, int attempt) implements Serializable {
        Request {
            tasks = List.copyOf(tasks);
            if (tasks.isEmpty()) throw new IllegalArgumentException("forked work request requires tasks");
            if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("worker id is required");
            if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        }
    }

    record Response(List<ScenarioTask> tasks, List<ExecutionResult> results) implements Serializable {
        Response {
            tasks = List.copyOf(tasks);
            results = List.copyOf(results);
        }
    }
}
