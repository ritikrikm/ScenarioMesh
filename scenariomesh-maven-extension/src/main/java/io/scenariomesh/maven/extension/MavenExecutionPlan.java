package io.scenariomesh.maven.extension;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Framework-neutral view of the Maven invocation that can be reproduced by a
 * transparent ScenarioMesh lifecycle takeover.
 *
 * <p>ScenarioMesh currently owns only standard default-lifecycle test phases.
 * Clean lifecycle phases may precede that lifecycle (for example {@code mvn clean verify})
 * because they do not alter which test execution is selected. Direct plugin goals,
 * custom/unqualified goals, and invocations that never enter the default lifecycle are
 * intentionally rejected here rather than being approximated as a lifecycle phase.</p>
 */
final class MavenExecutionPlan {
    private static final List<String> STANDARD_LIFECYCLE = List.of(
            "validate", "initialize", "generate-sources", "process-sources",
            "generate-resources", "process-resources", "compile", "process-classes",
            "generate-test-sources", "process-test-sources", "generate-test-resources",
            "process-test-resources", "test-compile", "process-test-classes", "test",
            "prepare-package", "package", "pre-integration-test", "integration-test",
            "post-integration-test", "verify", "install", "deploy");
    private static final Set<String> CLEAN_LIFECYCLE = Set.of("pre-clean", "clean", "post-clean");

    private static final Map<String, String> FAILSAFE_DEFAULT_PHASES = Map.of(
            "integration-test", "integration-test",
            "verify", "verify");

    private final int terminalPhaseIndex;
    private final String terminalPhase;

    private MavenExecutionPlan(int terminalPhaseIndex, String terminalPhase) {
        this.terminalPhaseIndex = terminalPhaseIndex;
        this.terminalPhase = terminalPhase;
    }

    static Optional<MavenExecutionPlan> from(MavenSession session) {
        return session == null ? Optional.empty() : fromGoals(session.getGoals());
    }

    /** Package-visible for deterministic invocation-shape tests. */
    static Optional<MavenExecutionPlan> fromGoals(List<String> goals) {
        if (goals == null || goals.isEmpty()) return Optional.empty();
        int highest = -1;
        String phase = null;

        for (String raw : goals) {
            if (raw == null || raw.isBlank()) continue;
            String goal = normalize(raw);

            int index = STANDARD_LIFECYCLE.indexOf(goal);
            if (index >= 0) {
                if (index > highest) {
                    highest = index;
                    phase = goal;
                }
                continue;
            }

            // "mvn clean verify" is still a reproducible lifecycle invocation.
            if (CLEAN_LIFECYCLE.contains(goal)) continue;

            // Everything else may be a direct plugin goal (plugin:goal,
            // group:artifact:version:goal, goal@execution) or a custom lifecycle/phase.
            // Transparent takeover must not reorder or collapse those semantics.
            return Optional.empty();
        }

        return highest < 0 ? Optional.empty() : Optional.of(new MavenExecutionPlan(highest, phase));
    }

    static MavenExecutionPlan through(String phase) {
        String normalized = normalize(phase);
        int index = STANDARD_LIFECYCLE.indexOf(normalized);
        if (index < 0) throw new IllegalArgumentException("Unknown standard Maven lifecycle phase: " + phase);
        return new MavenExecutionPlan(index, normalized);
    }

    boolean reaches(String phase) {
        int index = STANDARD_LIFECYCLE.indexOf(normalize(phase));
        return index >= 0 && index <= terminalPhaseIndex;
    }

    PluginParticipation failsafeParticipation(Plugin plugin) {
        if (plugin == null) return PluginParticipation.inactive();
        List<String> active = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<PluginExecution> activeExecutions = new ArrayList<>();

        for (PluginExecution execution : plugin.getExecutions()) {
            ExecutionParticipation result = classifyFailsafeExecution(execution);
            switch (result.state()) {
                case ACTIVE -> {
                    active.addAll(result.evidence());
                    activeExecutions.add(execution);
                }
                case UNKNOWN -> unknown.addAll(result.evidence());
                case INACTIVE -> { }
            }
        }

        if (!active.isEmpty()) return PluginParticipation.active(active, activeExecutions);
        if (!unknown.isEmpty()) return PluginParticipation.unknown(unknown);
        return PluginParticipation.inactive();
    }

    private ExecutionParticipation classifyFailsafeExecution(PluginExecution execution) {
        String executionId = execution.getId() == null ? "<unnamed>" : execution.getId();
        String explicitPhase = normalize(execution.getPhase());
        if (!explicitPhase.isEmpty()) {
            int index = STANDARD_LIFECYCLE.indexOf(explicitPhase);
            if (index < 0) {
                return ExecutionParticipation.unknown(List.of(
                        executionId + " uses unknown phase '" + execution.getPhase() + "'"));
            }
            return index <= terminalPhaseIndex
                    ? ExecutionParticipation.active(List.of(executionId + "@" + explicitPhase))
                    : ExecutionParticipation.inactive();
        }

        if (execution.getGoals() == null || execution.getGoals().isEmpty()) {
            return ExecutionParticipation.unknown(List.of(executionId + " has no phase and no goals"));
        }

        List<String> active = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String goal : execution.getGoals()) {
            String inferredPhase = FAILSAFE_DEFAULT_PHASES.get(normalize(goal));
            if (inferredPhase == null) {
                unknown.add(executionId + " has goal '" + goal + "' with no known lifecycle phase");
            } else if (reaches(inferredPhase)) {
                active.add(executionId + ":" + goal + "@" + inferredPhase);
            }
        }
        if (!active.isEmpty()) return ExecutionParticipation.active(active);
        if (!unknown.isEmpty()) return ExecutionParticipation.unknown(unknown);
        return ExecutionParticipation.inactive();
    }

    String terminalPhase() { return terminalPhase; }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    enum ParticipationState { ACTIVE, INACTIVE, UNKNOWN }

    record PluginParticipation(ParticipationState state,
                               List<String> evidence,
                               List<PluginExecution> activeExecutions) {
        PluginParticipation {
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            activeExecutions = List.copyOf(activeExecutions == null ? List.of() : activeExecutions);
        }
        static PluginParticipation active(List<String> evidence, List<PluginExecution> executions) {
            return new PluginParticipation(ParticipationState.ACTIVE, evidence, executions);
        }
        static PluginParticipation inactive() {
            return new PluginParticipation(ParticipationState.INACTIVE, List.of(), List.of());
        }
        static PluginParticipation unknown(List<String> evidence) {
            return new PluginParticipation(ParticipationState.UNKNOWN, evidence, List.of());
        }
    }

    private record ExecutionParticipation(ParticipationState state, List<String> evidence) {
        static ExecutionParticipation active(List<String> evidence) {
            return new ExecutionParticipation(ParticipationState.ACTIVE, List.copyOf(evidence));
        }
        static ExecutionParticipation inactive() {
            return new ExecutionParticipation(ParticipationState.INACTIVE, List.of());
        }
        static ExecutionParticipation unknown(List<String> evidence) {
            return new ExecutionParticipation(ParticipationState.UNKNOWN, List.copyOf(evidence));
        }
    }
}
