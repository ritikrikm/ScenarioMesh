package io.scenariomesh.maven.extension;

import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.maven.selection.MavenSelectionCodec;
import io.scenariomesh.maven.selection.SurefireTestSelection;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies the effective Surefire model without confusing Maven's generated
 * default lifecycle execution with a user-defined custom execution.
 */
final class SurefireCompatibility {
    static final String TESTNG_SUITE_XML_FILES_PROPERTY = "scenariomesh.testng.suiteXmlFiles";
    static final String INCLUDE_JUNIT5_ENGINES_PROPERTY = "includejunit5engines";
    static final String EXCLUDE_JUNIT5_ENGINES_PROPERTY = "excludejunit5engines";
    private static final String DEFAULT_TEST_EXECUTION_ID = "default-test";
    private static final String TEST_PHASE = "test";
    private static final String TEST_GOAL = "test";
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    private static final List<String> DEFAULT_INCLUDE_PATTERNS = List.of(
            "**/Test*.java", "**/*Test.java", "**/*Tests.java", "**/*TestCase.java");
    private static final List<String> DEFAULT_EXCLUDE_PATTERNS = List.of("**/*$*");

    private final EffectiveExecutorSystemProperties effectiveSystemProperties =
            new EffectiveExecutorSystemProperties();

    Analysis analyze(Plugin surefire) {
        return analyze(surefire, ignored -> null, new Properties());
    }

    Analysis analyze(Plugin surefire, Function<String, String> propertyResolver) {
        return analyze(surefire, propertyResolver, new Properties());
    }

    Analysis analyze(Plugin surefire,
                     Function<String, String> propertyResolver,
                     Properties mavenUserProperties) {
        List<String> reasons = new ArrayList<>();
        EffectiveSettings settings = new EffectiveSettings();
        List<Xpp3Dom> propertyConfigurations = new ArrayList<>();

        List<Dependency> dependencies = surefire.getDependencies();
        if (dependencies != null && !dependencies.isEmpty()) {
            reasons.add("maven-surefire-plugin declares custom provider/plugin dependencies");
        }

        Xpp3Dom pluginConfiguration = asDom(surefire.getConfiguration());
        if (pluginConfiguration != null) propertyConfigurations.add(pluginConfiguration);
        inspectConfiguration(pluginConfiguration, "maven-surefire-plugin configuration",
                settings, reasons, propertyResolver);

        List<PluginExecution> executions = surefire.getExecutions();
        int standardLifecycleExecutions = 0;
        if (executions != null) {
            for (PluginExecution execution : executions) {
                if (!isStandardLifecycleExecution(execution)) {
                    reasons.add("maven-surefire-plugin declares non-standard execution '" + executionId(execution) + "'");
                    continue;
                }
                standardLifecycleExecutions++;
                Xpp3Dom executionConfiguration = asDom(execution.getConfiguration());
                if (executionConfiguration != null) propertyConfigurations.add(executionConfiguration);
                inspectConfiguration(executionConfiguration,
                        "maven-surefire-plugin execution '" + DEFAULT_TEST_EXECUTION_ID + "'",
                        settings, reasons, propertyResolver);
            }
        }
        if (standardLifecycleExecutions > 1) {
            reasons.add("maven-surefire-plugin exposes multiple default-test executions; ScenarioMesh cannot prove single-execution equivalence");
        }

        EffectiveExecutorSystemProperties.Result externalProperties = effectiveSystemProperties.build(
                propertyConfigurations,
                projectBaseDirectory(propertyResolver, reasons),
                propertyResolver,
                mavenUserProperties,
                surefire.getVersion());
        if (!externalProperties.supported()) {
            reasons.add("maven-surefire-plugin system-property configuration cannot be reproduced safely: "
                    + externalProperties.reason());
        } else {
            settings.systemProperties.putAll(externalProperties.properties());
        }

        if (settings.includeJUnit5Engines.contains("junit-vintage")) {
            reasons.add("maven-surefire-plugin includes JUnit Vintage; generic JUnit 4 ownership is reserved for the P1 Vintage equivalence gate");
        }

        boolean explicitSelection = !settings.includes.isEmpty() || !settings.excludes.isEmpty();
        List<String> exactIncludes = settings.includes.isEmpty() ? DEFAULT_INCLUDE_PATTERNS : List.copyOf(settings.includes);
        List<String> exactExcludes = settings.excludes.isEmpty() ? DEFAULT_EXCLUDE_PATTERNS : List.copyOf(settings.excludes);
        List<String> includes;
        List<String> excludes;
        if (explicitSelection) {
            try {
                SurefireTestSelection.fromPatterns(exactIncludes, exactExcludes);
                settings.systemProperties.put(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS,
                        MavenSelectionCodec.encode(exactIncludes));
                settings.systemProperties.put(RuntimePropertyNames.MAVEN_EXCLUDED_TEST_PATTERNS,
                        MavenSelectionCodec.encode(exactExcludes));
                includes = List.of(".*");
                excludes = List.of();
            } catch (RuntimeException invalidSelection) {
                reasons.add("maven-surefire-plugin selection cannot be represented by Surefire TestListResolver: "
                        + safeMessage(invalidSelection));
                includes = List.of(".*");
                excludes = List.of();
            }
        } else {
            includes = defaultIncludeClassNameRegexes();
            excludes = defaultExcludeClassNameRegexes();
        }

        if (!settings.includeJUnit5Engines.isEmpty()) {
            settings.systemProperties.put(INCLUDE_JUNIT5_ENGINES_PROPERTY, String.join(",", settings.includeJUnit5Engines));
        }
        if (!settings.excludeJUnit5Engines.isEmpty()) {
            settings.systemProperties.put(EXCLUDE_JUNIT5_ENGINES_PROPERTY, String.join(",", settings.excludeJUnit5Engines));
        }

        return new Analysis(settings.explicitlySkipsTests, List.copyOf(reasons), includes, excludes,
                exactIncludes, exactExcludes, Map.copyOf(settings.systemProperties), List.copyOf(settings.dependenciesToScan));
    }

    static List<String> defaultIncludeClassNameRegexes() { return MavenClassNamePatterns.toRegexes(DEFAULT_INCLUDE_PATTERNS); }
    static List<String> defaultExcludeClassNameRegexes() { return MavenClassNamePatterns.toRegexes(DEFAULT_EXCLUDE_PATTERNS); }
    static List<String> defaultIncludePatterns() { return DEFAULT_INCLUDE_PATTERNS; }
    static List<String> defaultExcludePatterns() { return DEFAULT_EXCLUDE_PATTERNS; }

    private boolean isStandardLifecycleExecution(PluginExecution execution) {
        if (!DEFAULT_TEST_EXECUTION_ID.equals(trimToNull(execution.getId()))) return false;
        String phase = trimToNull(execution.getPhase());
        if (phase != null && !TEST_PHASE.equals(phase)) return false;
        List<String> goals = execution.getGoals();
        return goals != null && goals.size() == 1 && TEST_GOAL.equals(trimToNull(goals.get(0)));
    }

    private void inspectConfiguration(Object rawConfiguration, String location, EffectiveSettings settings,
                                      List<String> reasons, Function<String, String> propertyResolver) {
        Xpp3Dom configuration = asDom(rawConfiguration);
        if (configuration == null) return;
        for (Xpp3Dom child : configuration.getChildren()) {
            if (!hasMeaningfulValue(child)) continue;
            String name = child.getName();
            ExecutorConfigurationSemantics.Classification classification = ExecutorConfigurationSemantics.forSurefire(name);
            switch (classification.kind()) {
                case REPLACED_BY_SCENARIOMESH -> { }
                case REQUIRES_CAPABILITY -> reasons.add(location + " uses <" + name
                        + "> which requires ScenarioMesh capability '" + classification.capability() + "'");
                case UNKNOWN -> reasons.add(location + " uses unsupported configuration <" + name + ">");
                case PRESERVED -> preserveSetting(child, location, settings, reasons, propertyResolver);
            }
        }
    }

    private void preserveSetting(Xpp3Dom child, String location, EffectiveSettings settings,
                                 List<String> reasons, Function<String, String> propertyResolver) {
        switch (child.getName()) {
            case "includes" -> readPatternList(child, settings.includes, location, reasons, propertyResolver);
            case "excludes" -> readPatternList(child, settings.excludes, location, reasons, propertyResolver);
            case "includesFile" -> readSelectionFile(child, settings.includes, location, reasons, propertyResolver);
            case "excludesFile" -> readSelectionFile(child, settings.excludes, location, reasons, propertyResolver);
            case "dependenciesToScan" -> readDependenciesToScan(child, location, settings, reasons, propertyResolver);
            case "includeJUnit5Engines" -> readEngineList(child, settings.includeJUnit5Engines, location, reasons, propertyResolver);
            case "excludeJUnit5Engines" -> readEngineList(child, settings.excludeJUnit5Engines, location, reasons, propertyResolver);
            case "groups", "excludedGroups" -> readScalarSystemProperty(child, location, settings, reasons, propertyResolver);
            case "properties" -> readProviderProperties(child, location, settings, reasons, propertyResolver);
            case "suiteXmlFiles" -> readSuiteXmlFiles(child, location, settings, reasons, propertyResolver);
            case "skip", "skipTests" -> {
                Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                if (Boolean.TRUE.equals(value)) settings.explicitlySkipsTests = true;
            }
            case "useModulePath" -> {
                Boolean value = resolvedBoolean(child, location, reasons, propertyResolver);
                if (value != null && !Boolean.FALSE.equals(value)) reasons.add(location
                        + " uses <useModulePath> with a value ScenarioMesh does not yet reproduce");
            }
            default -> reasons.add(location + " has no preservation implementation for <" + child.getName() + ">");
        }
    }

    private void readScalarSystemProperty(Xpp3Dom node, String location, EffectiveSettings settings,
                                          List<String> reasons, Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " contains structured <" + node.getName() + "> group selection");
            return;
        }
        String value = resolve(node.getValue(), location + " <" + node.getName() + ">", reasons, propertyResolver);
        if (value == null) return;
        if (value.isBlank()) settings.systemProperties.remove(node.getName());
        else settings.systemProperties.put(node.getName(), value);
    }

    private void readEngineList(Xpp3Dom parent, Set<String> destination, String location,
                                List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"engine".equals(item.getName()) || item.getChildCount() > 0) {
                reasons.add(location + " contains unsupported JUnit engine selection inside <" + parent.getName() + ">");
                continue;
            }
            String value = resolve(item.getValue(), location + " <" + parent.getName() + ">", reasons, propertyResolver);
            if (value == null || value.isBlank() || value.contains(",")) {
                reasons.add(location + " contains an invalid JUnit engine id in <" + parent.getName() + ">");
            } else destination.add(value.trim());
        }
    }

    private void readSelectionFile(Xpp3Dom node, Set<String> destination, String location,
                                   List<String> reasons, Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " uses structured <" + node.getName() + "> and file selection semantics cannot be proven"); return;
        }
        String configuredPath = resolve(node.getValue(), location + " <" + node.getName() + ">", reasons, propertyResolver);
        if (configuredPath == null) return;
        String baseDirValue = propertyResolver.apply("project.basedir");
        if (baseDirValue == null || baseDirValue.isBlank()) {
            reasons.add(location + " uses <" + node.getName() + "> but Maven project.basedir is unavailable for exact relative-path resolution"); return;
        }
        Path baseDir;
        try { baseDir = Path.of(baseDirValue); }
        catch (RuntimeException invalidBaseDir) {
            reasons.add(location + " uses <" + node.getName() + "> but Maven project.basedir is invalid: " + invalidBaseDir.getMessage()); return;
        }
        ExternalSelectionFile.Analysis file = ExternalSelectionFile.read(baseDir, configuredPath, node.getName());
        if (!file.supported()) {
            reasons.add(location + " uses <" + node.getName() + "> that ScenarioMesh cannot reproduce: " + file.reason()); return;
        }
        destination.addAll(file.patterns());
    }

    private void readDependenciesToScan(Xpp3Dom parent, String location, EffectiveSettings settings,
                                        List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"dependency".equals(item.getName()) || item.getChildCount() > 0) {
                reasons.add(location + " contains unsupported structure inside <dependenciesToScan>");
                continue;
            }
            String value = resolve(item.getValue(), location + " <dependency>", reasons, propertyResolver);
            if (value == null || value.isBlank()) reasons.add(location + " contains a blank dependency scan pattern");
            else settings.dependenciesToScan.add(value.trim());
        }
    }

    private void readProviderProperties(Xpp3Dom parent, String location, EffectiveSettings settings,
                                        List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom property : parent.getChildren()) {
            if (!"configurationParameters".equals(property.getName()) || property.getChildCount() > 0) {
                reasons.add(location + " contains unsupported Surefire provider property <" + property.getName() + ">"); continue;
            }
            String value = resolve(property.getValue(), location + " <configurationParameters>", reasons, propertyResolver);
            if (value == null) continue;
            Properties parsed = new Properties();
            try { parsed.load(new StringReader(value)); }
            catch (IOException | IllegalArgumentException invalid) {
                reasons.add(location + " contains invalid Java-properties syntax in <configurationParameters>: " + invalid.getMessage()); continue;
            }
            parsed.forEach((key, configuredValue) -> settings.systemProperties.put(String.valueOf(key), String.valueOf(configuredValue)));
        }
    }

    private void readSuiteXmlFiles(Xpp3Dom parent, String location, EffectiveSettings settings,
                                   List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"suiteXmlFile".equals(item.getName()) || item.getChildCount() > 0) {
                reasons.add(location + " contains unsupported TestNG suite selection inside <suiteXmlFiles>"); continue;
            }
            String value = resolve(item.getValue(), location + " <suiteXmlFile>", reasons, propertyResolver);
            if (value == null || value.isBlank()) reasons.add(location + " contains an empty TestNG suite XML path");
            else settings.suiteXmlFiles.add(value);
        }
        if (!settings.suiteXmlFiles.isEmpty()) settings.systemProperties.put(TESTNG_SUITE_XML_FILES_PROPERTY, String.join("\n", settings.suiteXmlFiles));
    }

    private void readPatternList(Xpp3Dom parent, Set<String> destination, String location,
                                 List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"include".equals(item.getName()) && !"exclude".equals(item.getName())) {
                reasons.add(location + " contains unsupported <" + item.getName() + "> inside <" + parent.getName() + ">"); continue;
            }
            if (item.getChildCount() > 0) {
                reasons.add(location + " contains a structured selection pattern in <" + parent.getName() + ">"); continue;
            }
            String value = resolve(item.getValue(), location + " <" + parent.getName() + ">", reasons, propertyResolver);
            if (value == null || value.isBlank()) {
                reasons.add(location + " contains an empty selection pattern in <" + parent.getName() + ">"); continue;
            }
            destination.add(value);
        }
    }

    private Boolean resolvedBoolean(Xpp3Dom node, String location, List<String> reasons,
                                    Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " uses structured <" + node.getName() + "> and boolean semantics cannot be proven"); return null;
        }
        String value = resolve(node.getValue(), location + " <" + node.getName() + ">", reasons, propertyResolver);
        if (value == null) return null;
        if (value.isBlank()) return Boolean.FALSE;
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        reasons.add(location + " uses non-boolean <" + node.getName() + "> value '" + value + "'"); return null;
    }

    private String resolve(String raw, String location, List<String> reasons, Function<String, String> propertyResolver) {
        String value = trimToNull(raw);
        if (value == null) return "";
        Matcher matcher = PROPERTY_REFERENCE.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String replacement = propertyResolver.apply(matcher.group(1));
            if (replacement == null) {
                reasons.add(location + " references unresolved Maven property ${" + matcher.group(1) + "}"); return null;
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private Path projectBaseDirectory(Function<String, String> propertyResolver, List<String> reasons) {
        String value = propertyResolver.apply("project.basedir");
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            reasons.add("Maven project.basedir is invalid for executor property resolution: " + safeMessage(invalid));
            return null;
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private Xpp3Dom asDom(Object configuration) { return configuration instanceof Xpp3Dom dom ? dom : null; }
    private boolean hasMeaningfulValue(Xpp3Dom node) {
        String value = trimToNull(node.getValue()); String[] attributes = node.getAttributeNames();
        return value != null || node.getChildCount() > 0 || (attributes != null && attributes.length > 0);
    }
    private String executionId(PluginExecution execution) { String id = trimToNull(execution.getId()); return id == null ? "<unnamed>" : id; }
    private String trimToNull(String value) { if (value == null) return null; String trimmed = value.trim(); return trimmed.isEmpty() ? null : trimmed; }

    record Analysis(boolean explicitlySkipsTests, List<String> reasons, List<String> includeClassNameRegexes,
                    List<String> excludeClassNameRegexes, List<String> includedTestPatterns,
                    List<String> excludedTestPatterns, Map<String, String> systemProperties,
                    List<String> dependenciesToScan) {
        Analysis {
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            includeClassNameRegexes = List.copyOf(includeClassNameRegexes == null ? List.of() : includeClassNameRegexes);
            excludeClassNameRegexes = List.copyOf(excludeClassNameRegexes == null ? List.of() : excludeClassNameRegexes);
            includedTestPatterns = List.copyOf(includedTestPatterns == null ? List.of() : includedTestPatterns);
            excludedTestPatterns = List.copyOf(excludedTestPatterns == null ? List.of() : excludedTestPatterns);
            systemProperties = Map.copyOf(systemProperties == null ? Map.of() : systemProperties);
            dependenciesToScan = List.copyOf(dependenciesToScan == null ? List.of() : dependenciesToScan);
        }
    }

    private static final class EffectiveSettings {
        private final Set<String> includes = new LinkedHashSet<>();
        private final Set<String> excludes = new LinkedHashSet<>();
        private final Set<String> includeJUnit5Engines = new LinkedHashSet<>();
        private final Set<String> excludeJUnit5Engines = new LinkedHashSet<>();
        private final Map<String, String> systemProperties = new LinkedHashMap<>();
        private final Set<String> suiteXmlFiles = new LinkedHashSet<>();
        private final Set<String> dependenciesToScan = new LinkedHashSet<>();
        private boolean explicitlySkipsTests;
    }
}
