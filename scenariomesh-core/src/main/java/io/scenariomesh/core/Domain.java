package io.scenariomesh.core;
import java.net.URI;import java.time.*;import java.util.*;
public final class Domain { private Domain(){}
 public record ScenarioId(String value){public ScenarioId{Objects.requireNonNull(value);}}
 public record WorkerId(String value){public WorkerId{Objects.requireNonNull(value);}}
 public record RunId(UUID value){public static RunId create(){return new RunId(UUID.randomUUID());}}
 public record BuildFingerprint(String value){public BuildFingerprint{Objects.requireNonNull(value);}}
 public enum Framework { CUCUMBER_JUNIT_PLATFORM, CUCUMBER_JUNIT4, JUNIT5, TESTNG }
 public enum ResultStatus { PASSED, TEST_FAILURE, INFRASTRUCTURE_FAILURE, WORKER_FAILURE, DISCOVERY_FAILURE, CONFIGURATION_FAILURE }
 public enum WorkerStatus { STARTING, READY, BUSY, IDLE, DRAINING, STOPPING, STOPPED, UNHEALTHY, DEAD }
 public record ResourceRequirement(String name,int units){public ResourceRequirement{if(units<1)throw new IllegalArgumentException("units must be > 0");}}
 public record ScenarioTask(ScenarioId id,String displayName,Framework framework,URI source,Integer line,String engineId,Set<String> tags,Duration estimatedDuration,List<ResourceRequirement> resources){public ScenarioTask{Objects.requireNonNull(id);Objects.requireNonNull(displayName);Objects.requireNonNull(framework);tags=Set.copyOf(tags==null?Set.of():tags);resources=List.copyOf(resources==null?List.of():resources);}}
 public record ExecutionResult(ScenarioId scenarioId,ResultStatus status,Duration duration,WorkerId workerId,int attempt,Instant startedAt,Instant finishedAt,String failureMessage){public ExecutionResult{Objects.requireNonNull(scenarioId);Objects.requireNonNull(status);Objects.requireNonNull(duration);}}
}
