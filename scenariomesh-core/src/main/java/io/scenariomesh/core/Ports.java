package io.scenariomesh.core;
import io.scenariomesh.core.Domain.*;import java.util.*;import java.util.concurrent.*;
public final class Ports {private Ports(){}
 public interface ScenarioDiscoveryAdapter { String id(); boolean supports(ClassLoader classLoader); List<ScenarioTask> discover(DiscoveryRequest request) throws Exception; }
 public interface ScenarioExecutionAdapter { String id(); ExecutionResult execute(ScenarioTask task, ExecutionContext context) throws Exception; }
 public record DiscoveryRequest(ClassLoader classLoader,Map<String,String> properties){public DiscoveryRequest{properties=Map.copyOf(properties);}}
 public record ExecutionContext(RunId runId,WorkerId workerId,int attempt,Map<String,String> properties){public ExecutionContext{properties=Map.copyOf(properties);}}
 public interface TaskQueue {void add(ScenarioTask task); ScenarioTask poll(); int size();}
 public interface SchedulingStrategy {void load(Collection<ScenarioTask> tasks); ScenarioTask nextEligible(java.util.function.Predicate<ScenarioTask> eligible); int queued();}
 public interface ExecutionHistoryStore {OptionalLong durationMillis(ScenarioId id); void record(ExecutionResult result, BuildFingerprint fingerprint) throws Exception;}
 public interface BuildFingerprintStrategy {BuildFingerprint calculate() throws Exception;}
 public interface WorkerHandle {WorkerId id(); boolean alive(); void stop(Duration timeout) throws Exception;}
 public interface WorkerLauncher {WorkerHandle launch(WorkerLaunchRequest request) throws Exception;}
 public record WorkerLaunchRequest(WorkerId id,List<String> command,java.nio.file.Path workingDirectory,BuildFingerprint fingerprint){public WorkerLaunchRequest{command=List.copyOf(command);}}
}
