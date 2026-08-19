package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates the active Failsafe test execution and translates its class-level
 * selection into framework-neutral class-name regexes. Unknown execution
 * semantics remain pass-through material.
 */
final class FailsafeCompatibility {
    private static final List<String> DEFAULT_INCLUDE_PATTERNS = List.of(
            "**/IT*.java",
            "**/*IT.java",
            "**/*ITCase.java");

    Analysis analyze(Plugin plugin, MavenExecutionPlan.PluginParticipation participation) {
        List<String> reasons = new ArrayList<>();
        List<PluginExecution> testExecutions = participation.activeExecutions().stream()
                .filter(this::containsIntegrationTestGoal)
                .toList();

        if (testExecutions.isEmpty()) {
            return Analysis.unsupported("Failsafe participates in the invocation but no active integration-test goal could be isolated");
        }
        if (testExecutions.size() > 1) {
            return Analysis.unsupported("multiple active Failsafe integration-test executions are configured; ScenarioMesh will not collapse distinct Maven executions into one run");
        }

        SelectionAccumulator selection = new SelectionAccumulator();
        inspectConfiguration(plugin.getConfiguration(), "maven-failsafe-plugin configuration", selection, reasons);
        PluginExecution execution = testExecutions.get(0);
        inspectConfiguration(execution.getConfiguration(),
                "maven-failsafe-plugin execution '" + executionId(execution) + "'", selection, reasons);

        if (!reasons.isEmpty()) {
            return new Analysis(false, false, List.of(), List.of(), String.join("; ", reasons));
        }
        if (selection.explicitlySkipped) {
            return new Analysis(true, true, List.of(), List.of(), "Failsafe integration tests are explicitly skipped");
        }

        List<String> includes = selection.includes.isEmpty()
                ? DEFAULT_INCLUDE_PATTERNS.stream().map(FailsafeCompatibility::mavenClassPatternToRegex).toList()
                : selection.includes.stream().map(FailsafeCompatibility::mavenClassPatternToRegex).toList();
        List<String> excludes = selection.excludes.stream()
                .map(FailsafeCompatibility::mavenClassPatternToRegex)
                .toList();
        return new Analysis(true, false, includes, excludes,
                "one compatible Failsafe integration-test execution detected");
    }

    private boolean containsIntegrationTestGoal(PluginExecution execution) {
        return execution.getGoals() != null
                && execution.getGoals().stream().anyMatch(goal -> "integration-test".equals(trim(goal)));
    }

    private void inspectConfiguration(Object raw,
                                      String location,
                                      SelectionAccumulator selection,
                                      List<String> reasons) {
        if (!(raw instanceof Xpp3Dom configuration)) return;
        for (Xpp3Dom child : configuration.getChildren()) {
            if (!meaningful(child)) continue;
            switch (child.getName()) {
                case "includes" -> readPatternList(child, selection.includes, location, reasons);
                case "excludes" -> readPatternList(child, selection.excludes, location, reasons);
                case "skip", "skipITs", "skipTests" -> {
                    Boolean value = literalBoolean(child);
                    if (Boolean.TRUE.equals(value)) selection.explicitlySkipped = true;
                    else if (value == null) reasons.add(location + " uses non-literal <" + child.getName() + "> and skip semantics cannot be proven");
                }
                case "useModulePath" -> {
                    Boolean value = literalBoolean(child);
                    if (!Boolean.FALSE.equals(value)) reasons.add(location + " uses <useModulePath> with unsupported semantics");
                }
                case "forkCount", "reuseForks", "parallel", "threadCount", "threadCountClasses",
                        "threadCountMethods", "threadCountSuites", "perCoreThreadCount", "useUnlimitedThreads",
                        "parallelOptimized" -> {
                    // These only control Failsafe's own process/thread concurrency. ScenarioMesh replaces that
                    // execution mechanism with isolated workers and therefore intentionally does not reproduce them.
                }
                default -> reasons.add(location + " uses unsupported configuration <" + child.getName() + ">");
            }
        }
    }

    private void readPatternList(Xpp3Dom parent,
                                 Set<String> destination,
                                 String location,
                                 List<String> reasons) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"include".equals(item.getName()) && !"exclude".equals(item.getName())) {
                reasons.add(location + " contains unsupported <" + item.getName() + "> inside <" + parent.getName() + ">");
                continue;
            }
            String value = trim(item.getValue());
            if (value == null || value.contains("${")) {
                reasons.add(location + " contains a non-literal class selection pattern in <" + parent.getName() + ">");
            } else {
                destination.add(value);
            }
        }
    }

    static String mavenClassPatternToRegex(String pattern) {
        String normalized = pattern.replace('\\', '/');
        if (normalized.endsWith(".java")) normalized = normalized.substring(0, normalized.length() - 5);
        if (normalized.endsWith(".class")) normalized = normalized.substring(0, normalized.length() - 6);

        boolean optionalPackage = normalized.startsWith("**/");
        if (optionalPackage) normalized = normalized.substring(3);

        StringBuilder regex = new StringBuilder();
        if (optionalPackage) regex.append("(?:.*\\.)?");
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            if (ch == '/') {
                regex.append("\\.");
            } else if (ch == '*') {
                if (index + 1 < normalized.length() && normalized.charAt(index + 1) == '*') {
                    regex.append(".*");
                    index++;
                } else {
                    regex.append("[^.]*");
                }
            } else if (ch == '?') {
                regex.append("[^.]");
            } else if (".[]{}()+-^$|".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        return regex.toString();
    }

    private boolean meaningful(Xpp3Dom node) {
        return trim(node.getValue()) != null || node.getChildCount() > 0
                || (node.getAttributeNames() != null && node.getAttributeNames().length > 0);
    }

    private Boolean literalBoolean(Xpp3Dom node) {
        if (node.getChildCount() > 0) return null;
        String value = trim(node.getValue());
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        return null;
    }

    private String executionId(PluginExecution execution) {
        String id = trim(execution.getId());
        return id == null ? "<unnamed>" : id;
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record Analysis(boolean supported,
                    boolean explicitlySkipped,
                    List<String> includeClassNameRegexes,
                    List<String> excludeClassNameRegexes,
                    String reason) {
        Analysis {
            includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
            excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
        }
        static Analysis unsupported(String reason) {
            return new Analysis(false, false, List.of(), List.of(), reason);
        }
    }

    private static final class SelectionAccumulator {
        private final Set<String> includes = new LinkedHashSet<>();
        private final Set<String> excludes = new LinkedHashSet<>();
        private boolean explicitlySkipped;
    }
}
