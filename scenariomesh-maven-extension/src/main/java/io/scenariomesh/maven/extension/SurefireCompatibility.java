package io.scenariomesh.maven.extension;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.io.StringReader;
import java.io.IOException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies the effective Surefire model without confusing Maven's generated
 * default lifecycle execution with a user-defined custom execution.
 */
final class SurefireCompatibility {
    static final String TESTNG_SUITE_XML_FILES_PROPERTY = "scenariomesh.testng.suiteXmlFiles";
    private static final String DEFAULT_TEST_EXECUTION_ID = "default-test";
    private static final String TEST_PHASE = "test";
    private static final String TEST_GOAL = "test";
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    private static final List<String> DEFAULT_INCLUDE_PATTERNS = List.of(
            "**/Test*.java",
            "**/*Test.java",
            "**/*Tests.java",
            "**/*TestCase.java");
    private static final List<String> DEFAULT_EXCLUDE_PATTERNS = List.of("**/*$*");

    Analysis analyze(Plugin surefire) {
        return analyze(surefire, ignored -> null);
    }

    Analysis analyze(Plugin surefire, Function<String, String> propertyResolver) {
        List<String> reasons = new ArrayList<>();
        EffectiveSettings settings = new EffectiveSettings();

        List<Dependency> dependencies = surefire.getDependencies();
        if (dependencies != null && !dependencies.isEmpty()) {
            reasons.add("maven-surefire-plugin declares custom provider/plugin dependencies");
        }

        inspectConfiguration(
                surefire.getConfiguration(), "maven-surefire-plugin configuration", settings, reasons, propertyResolver);

        List<PluginExecution> executions = surefire.getExecutions();
        int standardLifecycleExecutions = 0;
        if (executions != null) {
            for (PluginExecution execution : executions) {
                if (!isStandardLifecycleExecution(execution)) {
                    reasons.add("maven-surefire-plugin declares non-standard execution '"
                            + executionId(execution) + "'");
                    continue;
                }

                standardLifecycleExecutions++;
                inspectConfiguration(
                        execution.getConfiguration(),
                        "maven-surefire-plugin execution '" + DEFAULT_TEST_EXECUTION_ID + "'",
                        settings,
                        reasons,
                        propertyResolver);
            }
        }

        if (standardLifecycleExecutions > 1) {
            reasons.add("maven-surefire-plugin exposes multiple default-test executions; "
                    + "ScenarioMesh cannot prove single-execution equivalence");
        }

        List<String> includes = settings.includes.isEmpty()
                ? defaultIncludeClassNameRegexes()
                : MavenClassNamePatterns.toRegexes(List.copyOf(settings.includes));
        List<String> excludes = settings.excludes.isEmpty()
                ? defaultExcludeClassNameRegexes()
                : MavenClassNamePatterns.toRegexes(List.copyOf(settings.excludes));

        return new Analysis(
                settings.explicitlySkipsTests,
                List.copyOf(reasons),
                includes,
                excludes,
                Map.copyOf(settings.systemProperties));
    }

    static List<String> defaultIncludeClassNameRegexes() {
        return MavenClassNamePatterns.toRegexes(DEFAULT_INCLUDE_PATTERNS);
    }

    static List<String> defaultExcludeClassNameRegexes() {
        return MavenClassNamePatterns.toRegexes(DEFAULT_EXCLUDE_PATTERNS);
    }

    private boolean isStandardLifecycleExecution(PluginExecution execution) {
        if (!DEFAULT_TEST_EXECUTION_ID.equals(trimToNull(execution.getId()))) return false;
        String phase = trimToNull(execution.getPhase());
        if (phase != null && !TEST_PHASE.equals(phase)) return false;
        List<String> goals = execution.getGoals();
        return goals != null && goals.size() == 1 && TEST_GOAL.equals(trimToNull(goals.get(0)));
    }

    private void inspectConfiguration(Object rawConfiguration,
                                      String location,
                                      EffectiveSettings settings,
                                      List<String> reasons,
                                      Function<String, String> propertyResolver) {
        Xpp3Dom configuration = asDom(rawConfiguration);
        if (configuration == null) return;

        for (Xpp3Dom child : configuration.getChildren()) {
            if (!hasMeaningfulValue(child)) continue;

            String name = child.getName();
            ExecutorConfigurationSemantics.Classification classification =
                    ExecutorConfigurationSemantics.forSurefire(name);

            switch (classification.kind()) {
                case REPLACED_BY_SCENARIOMESH -> {
                    // Surefire's concurrency layer is replaced by ScenarioMesh workers.
                }
                case REQUIRES_CAPABILITY -> reasons.add(location + " uses <" + name
                        + "> which requires ScenarioMesh capability '" + classification.capability() + "'");
                case UNKNOWN -> reasons.add(location + " uses unsupported configuration <" + name + ">");
                case PRESERVED -> preserveSetting(child, location, settings, reasons, propertyResolver);
            }
        }
    }

    private void preserveSetting(Xpp3Dom child,
                                 String location,
                                 EffectiveSettings settings,
                                 List<String> reasons,
                                 Function<String, String> propertyResolver) {
        switch (child.getName()) {
            case "includes" -> readPatternList(child, settings.includes, location, reasons, propertyResolver);
            case "excludes" -> readPatternList(child, settings.excludes, location, reasons, propertyResolver);
            case "systemPropertyVariables" -> readSystemProperties(child, location, settings, reasons, propertyResolver);
            case "properties" -> readProviderProperties(child, location, settings, reasons, propertyResolver);
            case "suiteXmlFiles" -> readSuiteXmlFiles(child, location, settings, reasons, propertyResolver);
            case "skip", "skipTests" -> {
                Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                if (Boolean.TRUE.equals(value)) settings.explicitlySkipsTests = true;
            }
            case "useModulePath" -> {
                Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                if (value != null && !Boolean.FALSE.equals(value)) {
                    reasons.add(location
                            + " uses <useModulePath> with a value ScenarioMesh does not yet reproduce");
                }
            }
            default -> reasons.add(location + " has no preservation implementation for <" + child.getName() + ">");
        }
    }

    private void readProviderProperties(Xpp3Dom parent,
                                        String location,
                                        EffectiveSettings settings,
                                        List<String> reasons,
                                        Function<String, String> propertyResolver) {
        for (Xpp3Dom property : parent.getChildren()) {
            if (!"configurationParameters".equals(property.getName()) || property.getChildCount() > 0) {
                reasons.add(location + " contains unsupported Surefire provider property <"
                        + property.getName() + ">");
                continue;
            }
            String value = resolve(property.getValue(), location + " <configurationParameters>", reasons, propertyResolver);
            if (value == null) continue;
            Properties parsed = new Properties();
            try {
                parsed.load(new StringReader(value));
            } catch (IOException | IllegalArgumentException invalid) {
                reasons.add(location + " contains invalid Java-properties syntax in <configurationParameters>: "
                        + invalid.getMessage());
                continue;
            }
            parsed.forEach((key, configuredValue) ->
                    settings.systemProperties.put(String.valueOf(key), String.valueOf(configuredValue)));
        }
    }

    private void readSuiteXmlFiles(Xpp3Dom parent,
                                   String location,
                                   EffectiveSettings settings,
                                   List<String> reasons,
                                   Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"suiteXmlFile".equals(item.getName()) || item.getChildCount() > 0) {
                reasons.add(location + " contains unsupported TestNG suite selection inside <suiteXmlFiles>");
                continue;
            }
            String value = resolve(item.getValue(), location + " <suiteXmlFile>", reasons, propertyResolver);
            if (value == null || value.isBlank()) {
                reasons.add(location + " contains an empty TestNG suite XML path");
            } else {
                settings.suiteXmlFiles.add(value);
            }
        }
        if (!settings.suiteXmlFiles.isEmpty()) {
            settings.systemProperties.put(TESTNG_SUITE_XML_FILES_PROPERTY,
                    String.join("\n", settings.suiteXmlFiles));
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
            if (item.getChildCount() > 0) {
                reasons.add(location + " contains a structured class selection pattern in <"
                        + parent.getName() + ">");
                continue;
            }
            String value = resolve(item.getValue(), location + " <" + parent.getName() + ">", reasons, propertyResolver);
            if (value == null || value.isBlank()) {
                reasons.add(location + " contains an empty class selection pattern in <" + parent.getName() + ">");
                continue;
            }
            try {
                MavenClassNamePatterns.toRegex(value);
                destination.add(value);
            } catch (IllegalArgumentException unsupportedPattern) {
                reasons.add(location + " uses unsupported Maven class selection pattern '" + value
                        + "': " + unsupportedPattern.getMessage());
            }
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
            if (value != null) settings.systemProperties.put(property.getName(), value);
        }
    }

    private Boolean resolvedBoolean(Xpp3Dom node,
                                    String location,
                                    List<String> reasons,
                                    Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " uses structured <" + node.getName()
                    + "> and boolean semantics cannot be proven");
            return null;
        }
        String value = resolve(node.getValue(), location + " <" + node.getName() + ">", reasons, propertyResolver);
        if (value == null) return null;
        if (value.isBlank()) return Boolean.FALSE;
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        reasons.add(location + " uses non-boolean <" + node.getName() + "> value '" + value + "'");
        return null;
    }

    private String resolve(String raw,
                           String location,
                           List<String> reasons,
                           Function<String, String> propertyResolver) {
        String value = trimToNull(raw);
        if (value == null) return "";
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

    private Xpp3Dom asDom(Object configuration) {
        return configuration instanceof Xpp3Dom dom ? dom : null;
    }

    private boolean hasMeaningfulValue(Xpp3Dom node) {
        String value = trimToNull(node.getValue());
        String[] attributes = node.getAttributeNames();
        return value != null || node.getChildCount() > 0 || (attributes != null && attributes.length > 0);
    }

    private String executionId(PluginExecution execution) {
        String id = trimToNull(execution.getId());
        return id == null ? "<unnamed>" : id;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record Analysis(
            boolean explicitlySkipsTests,
            List<String> reasons,
            List<String> includeClassNameRegexes,
            List<String> excludeClassNameRegexes,
            Map<String, String> systemProperties) {
        Analysis {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            includeClassNameRegexes = List.copyOf(
                    includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
            excludeClassNameRegexes = List.copyOf(
                    excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
            systemProperties = Map.copyOf(systemProperties == null ? Map.of() : systemProperties);
        }
    }

    private static final class EffectiveSettings {
        private final Set<String> includes = new LinkedHashSet<>();
        private final Set<String> excludes = new LinkedHashSet<>();
        private final Map<String, String> systemProperties = new LinkedHashMap<>();
        private final Set<String> suiteXmlFiles = new LinkedHashSet<>();
        private boolean explicitlySkipsTests;
    }
}
