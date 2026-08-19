package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates the active Failsafe test execution and translates the subset of
 * Failsafe semantics that ScenarioMesh can reproduce exactly. Unknown semantics
 * remain pass-through material rather than being ignored.
 */
final class FailsafeCompatibility {
    private static final List<String> DEFAULT_INCLUDE_PATTERNS = List.of(
            "**/IT*.java",
            "**/*IT.java",
            "**/*ITCase.java");
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    Analysis analyze(Plugin plugin,
                     MavenExecutionPlan.PluginParticipation participation,
                     Function<String, String> propertyResolver) {
        List<String> reasons = new ArrayList<>();
        List<PluginExecution> testExecutions = participation.activeExecutions().stream()
                .filter(this::containsIntegrationTestGoal)
                .toList();

        if (testExecutions.isEmpty()) {
            return Analysis.unsupported(
                    "Failsafe participates in the invocation but no active integration-test goal could be isolated");
        }
        if (testExecutions.size() > 1) {
            return Analysis.unsupported(
                    "multiple active Failsafe integration-test executions are configured; "
                            + "ScenarioMesh will not collapse distinct Maven executions into one run");
        }

        EffectiveSettings settings = new EffectiveSettings();
        inspectConfiguration(
                plugin.getConfiguration(),
                "maven-failsafe-plugin configuration",
                settings,
                reasons,
                propertyResolver);

        PluginExecution execution = testExecutions.get(0);
        inspectConfiguration(
                execution.getConfiguration(),
                "maven-failsafe-plugin execution '" + executionId(execution) + "'",
                settings,
                reasons,
                propertyResolver);

        if (settings.rerunFailingTestsCount > 0) {
            reasons.add("rerunFailingTestsCount resolves to " + settings.rerunFailingTestsCount
                    + "; ScenarioMesh will not risk duplicating retries until exact Failsafe retry semantics are implemented");
        }
        if (!reasons.isEmpty()) {
            return new Analysis(false, false, List.of(), List.of(), List.of(), Map.of(), false,
                    String.join("; ", reasons));
        }
        if (settings.explicitlySkipped) {
            return new Analysis(true, true, List.of(), List.of(), List.of(), Map.of(),
                    settings.testFailureIgnore,
                    "Failsafe integration tests are explicitly skipped");
        }

        List<String> includes = settings.includes.isEmpty()
                ? DEFAULT_INCLUDE_PATTERNS.stream().map(FailsafeCompatibility::mavenClassPatternToRegex).toList()
                : settings.includes.stream().map(FailsafeCompatibility::mavenClassPatternToRegex).toList();
        List<String> excludes = settings.excludes.stream()
                .map(FailsafeCompatibility::mavenClassPatternToRegex)
                .toList();

        return new Analysis(
                true,
                false,
                includes,
                excludes,
                List.copyOf(settings.jvmArgs),
                Map.copyOf(settings.systemProperties),
                settings.testFailureIgnore,
                "one compatible Failsafe integration-test execution detected");
    }

    private boolean containsIntegrationTestGoal(PluginExecution execution) {
        return execution.getGoals() != null
                && execution.getGoals().stream().anyMatch(goal -> "integration-test".equals(trim(goal)));
    }

    private void inspectConfiguration(Object raw,
                                      String location,
                                      EffectiveSettings settings,
                                      List<String> reasons,
                                      Function<String, String> propertyResolver) {
        if (!(raw instanceof Xpp3Dom configuration)) {
            return;
        }
        for (Xpp3Dom child : configuration.getChildren()) {
            if (!meaningful(child)) {
                continue;
            }
            switch (child.getName()) {
                case "includes" -> readPatternList(child, settings.includes, location, reasons, propertyResolver);
                case "excludes" -> readPatternList(child, settings.excludes, location, reasons, propertyResolver);
                case "skip", "skipITs", "skipTests" -> {
                    Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                    if (Boolean.TRUE.equals(value)) {
                        settings.explicitlySkipped = true;
                    }
                }
                case "useModulePath" -> {
                    Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                    if (value != null && !Boolean.FALSE.equals(value)) {
                        reasons.add(location + " uses <useModulePath> with unsupported semantics");
                    }
                }
                case "argLine" -> readArgLine(child, location, settings, reasons, propertyResolver);
                case "systemPropertyVariables" -> readSystemProperties(
                        child, location, settings, reasons, propertyResolver);
                case "testFailureIgnore" -> {
                    Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                    if (value != null) {
                        settings.testFailureIgnore = value;
                    }
                }
                case "rerunFailingTestsCount" -> {
                    Integer value = resolvedNonNegativeInteger(child, location, reasons, propertyResolver);
                    if (value != null) {
                        settings.rerunFailingTestsCount = value;
                    }
                }
                case "forkCount", "reuseForks", "parallel", "threadCount", "threadCountClasses",
                        "threadCountMethods", "threadCountSuites", "perCoreThreadCount", "useUnlimitedThreads",
                        "parallelOptimized" -> {
                    // These control Failsafe's own concurrency implementation. ScenarioMesh replaces
                    // that mechanism with isolated worker JVMs, so copying these values would be wrong.
                }
                default -> reasons.add(location + " uses unsupported configuration <" + child.getName() + ">");
            }
        }
    }

    private void readArgLine(Xpp3Dom node,
                             String location,
                             EffectiveSettings settings,
                             List<String> reasons,
                             Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " contains a structured <argLine> that cannot be reproduced safely");
            return;
        }
        String resolved = resolve(node.getValue(), location + " <argLine>", reasons, propertyResolver);
        if (resolved == null || resolved.isBlank()) {
            settings.jvmArgs.clear();
            return;
        }
        if (resolved.contains("@{")) {
            reasons.add(location + " uses late Failsafe property replacement in <argLine>; "
                    + "ScenarioMesh cannot prove the resolved JVM command line");
            return;
        }
        try {
            String[] translated = CommandLineUtils.translateCommandline(resolved);
            settings.jvmArgs.clear();
            settings.jvmArgs.addAll(List.of(translated));
        } catch (Exception exception) {
            reasons.add(location + " contains an <argLine> that cannot be tokenized safely: " + exception.getMessage());
        }
    }

    private void readSystemProperties(Xpp3Dom parent,
                                      String location,
                                      EffectiveSettings settings,
                                      List<String> reasons,
                                      Function<String, String> propertyResolver) {
        for (Xpp3Dom property : parent.getChildren()) {
            if (property.getChildCount() > 0) {
                reasons.add(location + " contains nested system property '" + property.getName() + "'");
                continue;
            }
            String value = resolve(
                    property.getValue(),
                    location + " system property '" + property.getName() + "'",
                    reasons,
                    propertyResolver);
            if (value != null) {
                settings.systemProperties.put(property.getName(), value);
            }
        }
    }

    private void readPatternList(Xpp3Dom parent,
                                 Set<String> destination,
                                 String location,
                                 List<String> reasons,
                                 Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"include".equals(item.getName()) && !"exclude".equals(item.getName())) {
                reasons.add(location + " contains unsupported <" + item.getName() + "> inside <"
                        + parent.getName() + ">");
                continue;
            }
            String value = resolve(item.getValue(), location + " <" + parent.getName() + ">", reasons, propertyResolver);
            if (value == null || value.isBlank()) {
                reasons.add(location + " contains an empty class selection pattern in <" + parent.getName() + ">");
            } else {
                destination.add(value);
            }
        }
    }

    private Boolean resolvedBoolean(Xpp3Dom node,
                                    String location,
                                    List<String> reasons,
                                    Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " uses structured <" + node.getName() + "> and boolean semantics cannot be proven");
            return null;
        }
        String value = resolve(node.getValue(), location + " <" + node.getName() + ">", reasons, propertyResolver);
        if (value == null || value.isBlank()) {
            return Boolean.FALSE;
        }
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        reasons.add(location + " uses non-boolean <" + node.getName() + "> value '" + value + "'");
        return null;
    }

    private Integer resolvedNonNegativeInteger(Xpp3Dom node,
                                               String location,
                                               List<String> reasons,
                                               Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " uses structured <" + node.getName() + "> and integer semantics cannot be proven");
            return null;
        }
        String value = resolve(node.getValue(), location + " <" + node.getName() + ">", reasons, propertyResolver);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                reasons.add(location + " uses negative <" + node.getName() + "> value '" + value + "'");
                return null;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            reasons.add(location + " uses non-integer <" + node.getName() + "> value '" + value + "'");
            return null;
        }
    }

    private String resolve(String raw,
                           String location,
                           List<String> reasons,
                           Function<String, String> propertyResolver) {
        String value = trim(raw);
        if (value == null) {
            return "";
        }
        Matcher matcher = PROPERTY_REFERENCE.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String replacement = propertyResolver.apply(matcher.group(1));
            if (replacement == null) {
                reasons.add(location + " references unresolved Maven property ${" + matcher.group(1) + "}");
                return null;
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
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
                    List<String> jvmArgs,
                    Map<String, String> systemProperties,
                    boolean testFailureIgnore,
                    String reason) {
        Analysis {
            includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
            excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
            jvmArgs = List.copyOf(jvmArgs == null ? List.of() : jvmArgs);
            systemProperties = Map.copyOf(systemProperties == null ? Map.of() : systemProperties);
        }

        static Analysis unsupported(String reason) {
            return new Analysis(false, false, List.of(), List.of(), List.of(), Map.of(), false, reason);
        }
    }

    private static final class EffectiveSettings {
        private final Set<String> includes = new LinkedHashSet<>();
        private final Set<String> excludes = new LinkedHashSet<>();
        private final List<String> jvmArgs = new ArrayList<>();
        private final Map<String, String> systemProperties = new LinkedHashMap<>();
        private boolean explicitlySkipped;
        private boolean testFailureIgnore;
        private int rerunFailingTestsCount;
    }
}
