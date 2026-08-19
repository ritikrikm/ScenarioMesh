package io.scenariomesh.maven.extension;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Small, framework-neutral model of the standard Maven lifecycle portion reached
 * by the current invocation. Plugin compatibility checks use this model instead
 * of treating "plugin is present in the POM" as "plugin participates in this run".
 *
 * <p>The model is deliberately conservative. An execution whose phase cannot be
 * established is UNKNOWN rather than assumed inactive.</p>
 */
final class MavenExecutionPlan {
    private static final List<String> STANDARD_LIFECYCLE = List.of(
            "validate",
            "initialize",
            "generate-sources",
            "process-sources",
            "generate-resources",
            "process-resources",
            "compile",
            "process-classes",
            "generate-test-sources",
            "process-test-sources",
            "generate-test-resources",
            "process-test-resources",
            "test-compile",
            "process-test-classes",
            "test",
            "prepare-package",
            "package",
            "pre-integration-test",
            "integration-test",
            "post-integration-test",
            "verify",
            "install",
            "deploy"
    );

    private static final Map<String, String> FAILSAFE_DEFAULT_PHASES = Map.of(
            "integration-test", "integration-test",
            "verify", "verify"
    );

    private final int terminalPhaseIndex;
    private final String terminalPhase;

    private MavenExecutionPlan(int terminalPhaseIndex, String terminalPhase) {
        this.terminalPhaseIndex = terminalPhaseIndex;
        this.terminalPhase = terminalPhase;
    }

    static Optional<MavenExecutionPlan> from(MavenSession session) {
        if (session == null || session.getGoals() == null) {
            return Optional.empty();
        }
        int highest = -1;
        String phase = null;
        for (String raw : session.getGoals()) {
            if (raw == null) {
                continue;
            }
            String goal = raw.trim().toLowerCase(Locale.ROOT);
            int index = STANDARD_LIFECYCLE.indexOf(goal);
            if (index > highest) {
                highest = index;
                phase = goal;
            }
        }
        return highest < 0 ? Optional.empty() : Optional.of(new MavenExecutionPlan(highest, phase));
    }

    static MavenExecutionPlan through(String phase) {
        String normalized = phase == null ? "" : phase.trim().toLowerCase(Locale.ROOT);
        int index = STANDARD_LIFECYCLE.indexOf(normalized);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown standard Maven lifecycle phase: " + phase);
        }
        return new MavenExecutionPlan(index, normalized);
    }

    boolean reaches(String phase) {
        int index = STANDARD_LIFECYCLE.indexOf(normalize(phase));
        return index >= 0 && index <= terminalPhaseIndex;
    }

    PluginParticipation failsafeParticipation(Plugin plugin) {
        if (plugin == null) {
            return PluginParticipation.inactive();
        }
        List<String> active = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (PluginExecution execution : plugin.getExecutions()) {
            String executionId = execution.getId() == null ? "<unnamed>" : execution.getId();
            String explicitPhase = normalize(execution.getPhase());
            if (!explicitPhase.isEmpty()) {
                int index = STANDARD_LIFECYCLE.indexOf(explicitPhase);
                if (index < 0) {
                    unknown.add(executionId + " uses unknown phase '" + execution.getPhase() + "'");
                } else if (index <= terminalPhaseIndex) {
                    active.add(executionId + "@" + explicitPhase);
                }
                continue;
            }

            if (execution.getGoals() == null || execution.getGoals().isEmpty()) {
                unknown.add(executionId + " has no phase and no goals");
                continue;
            }

            for (String goal : execution.getGoals()) {
                String inferredPhase = FAILSAFE_DEFAULT_PHASES.get(normalize(goal));
                if (inferredPhase == null) {
                    unknown.add(executionId + " has goal '" + goal + "' with no known lifecycle phase");
                    continue;
                }
                if (reaches(inferredPhase)) {
                    active.add(executionId + ":" + goal + "@" + inferredPhase);
                }
            }
        }

        if (!active.isEmpty()) {
            return PluginParticipation.active(active);
        }
        if (!unknown.isEmpty()) {
            return PluginParticipation.unknown(unknown);
        }
        return PluginParticipation.inactive();
    }

    String terminalPhase() {
        return terminalPhase;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    enum ParticipationState {
        ACTIVE,
        INACTIVE,
        UNKNOWN
    }

    record PluginParticipation(ParticipationState state, List<String> evidence) {
        PluginParticipation {
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }

        static PluginParticipation active(List<String> evidence) {
            return new PluginParticipation(ParticipationState.ACTIVE, evidence);
        }

        static PluginParticipation inactive() {
            return new PluginParticipation(ParticipationState.INACTIVE, List.of());
        }

        static PluginParticipation unknown(List<String> evidence) {
            return new PluginParticipation(ParticipationState.UNKNOWN, evidence);
        }
    }
}
