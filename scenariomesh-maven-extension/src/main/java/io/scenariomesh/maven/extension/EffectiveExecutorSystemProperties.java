package io.scenariomesh.maven.extension;

import org.apache.maven.artifact.versioning.ComparableVersion;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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
 * Builds the effective Surefire/Failsafe provider system properties using the
 * precedence documented by Maven Surefire/Failsafe rather than incidental map
 * insertion order in the compatibility analyzers.
 *
 * <p>Precedence, from lowest to highest, is:
 * deprecated {@code systemProperties}, {@code systemPropertiesFile},
 * {@code systemPropertyVariables}, then Maven-session user properties when
 * {@code promoteUserPropertiesToSystemProperties} is enabled.</p>
 *
 * <p>Plugin-level and execution-level configurations are accepted in effective
 * order. Each source category is composed first and Maven user properties are
 * promoted exactly once at the end. This prevents a later execution layer from
 * accidentally overriding a {@code -D} user property.</p>
 *
 * <p>This class deliberately does not reinterpret {@code argLine}. Properties
 * whose JVM behavior is fixed at VM startup remain ordinary provider system
 * properties when configured through the system-property parameters, matching
 * Surefire's documented distinction rather than silently upgrading them into
 * {@code -D} launch arguments.</p>
 */
final class EffectiveExecutorSystemProperties {
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    private static final ComparableVersion SYSTEM_PROPERTY_VARIABLES_SINCE = new ComparableVersion("2.5");
    private static final ComparableVersion SYSTEM_PROPERTIES_FILE_SINCE = new ComparableVersion("2.8.2");
    private static final ComparableVersion PROMOTION_TOGGLE_SINCE = new ComparableVersion("3.4.0");
    private static final Set<String> VM_STARTUP_ONLY = Set.of(
            "java.library.path",
            "file.encoding",
            "jdk.map.althashing.threshold",
            "line.separator");

    enum Origin {
        LEGACY_SYSTEM_PROPERTIES,
        SYSTEM_PROPERTIES_FILE,
        SYSTEM_PROPERTY_VARIABLES,
        MAVEN_USER_PROPERTY
    }

    record Value(String value, Origin origin) { }

    record Result(boolean supported,
                  Map<String, String> properties,
                  Map<String, Origin> origins,
                  Set<String> vmStartupOnlyProperties,
                  String reason) {
        static Result unsupported(String reason) {
            return new Result(false, Map.of(), Map.of(), Set.of(), reason);
        }
    }

    Result build(Xpp3Dom configuration,
                 Path projectBaseDirectory,
                 Function<String, String> propertyResolver,
                 Properties mavenUserProperties) {
        return build(configuration == null ? List.of() : List.of(configuration),
                projectBaseDirectory, propertyResolver, mavenUserProperties, null);
    }

    Result build(List<Xpp3Dom> configurations,
                 Path projectBaseDirectory,
                 Function<String, String> propertyResolver,
                 Properties mavenUserProperties) {
        return build(configurations, projectBaseDirectory, propertyResolver, mavenUserProperties, null);
    }

    Result build(List<Xpp3Dom> configurations,
                 Path projectBaseDirectory,
                 Function<String, String> propertyResolver,
                 Properties mavenUserProperties,
                 String executorVersion) {
        Function<String, String> resolver = propertyResolver == null ? ignored -> null : propertyResolver;
        List<Xpp3Dom> layers = configurations == null ? List.of() : configurations.stream()
                .filter(java.util.Objects::nonNull)
                .toList();

        Result versionSupport = validateParameterVersions(layers, executorVersion);
        if (!versionSupport.supported()) return versionSupport;

        LinkedHashMap<String, Value> legacyValues = new LinkedHashMap<>();
        LinkedHashMap<String, Value> fileValues = new LinkedHashMap<>();
        LinkedHashMap<String, Value> variableValues = new LinkedHashMap<>();
        boolean promoteUserProperties = true;

        for (Xpp3Dom configuration : layers) {
            Xpp3Dom legacy = configuration.getChild("systemProperties");
            if (legacy != null && meaningful(legacy)) {
                Result result = readLegacySystemProperties(legacy, resolver, legacyValues);
                if (!result.supported()) return result;
            }

            Xpp3Dom file = configuration.getChild("systemPropertiesFile");
            if (file != null && meaningful(file)) {
                LinkedHashMap<String, Value> replacement = new LinkedHashMap<>();
                Result result = readSystemPropertiesFile(file, projectBaseDirectory, resolver, replacement);
                if (!result.supported()) return result;
                fileValues.clear();
                fileValues.putAll(replacement);
            }

            Xpp3Dom variables = configuration.getChild("systemPropertyVariables");
            if (variables != null && meaningful(variables)) {
                Result result = readSystemPropertyVariables(variables, resolver, variableValues);
                if (!result.supported()) return result;
            }

            Xpp3Dom promotion = configuration.getChild("promoteUserPropertiesToSystemProperties");
            if (promotion != null && meaningful(promotion)) {
                Boolean parsed = readPromotionFlag(promotion, resolver);
                if (parsed == null) {
                    return Result.unsupported("<promoteUserPropertiesToSystemProperties> must resolve to true or false");
                }
                promoteUserProperties = parsed;
            }
        }

        LinkedHashMap<String, Value> effective = new LinkedHashMap<>();
        effective.putAll(legacyValues);
        effective.putAll(fileValues);
        effective.putAll(variableValues);

        if (promoteUserProperties && mavenUserProperties != null) {
            for (Map.Entry<Object, Object> entry : mavenUserProperties.entrySet()) {
                effective.put(String.valueOf(entry.getKey()),
                        new Value(String.valueOf(entry.getValue()), Origin.MAVEN_USER_PROPERTY));
            }
        }

        return supported(effective);
    }

    private Result validateParameterVersions(List<Xpp3Dom> configurations, String executorVersion) {
        boolean variables = containsMeaningful(configurations, "systemPropertyVariables");
        boolean file = containsMeaningful(configurations, "systemPropertiesFile");
        boolean promotion = containsMeaningful(configurations, "promoteUserPropertiesToSystemProperties");
        if (!variables && !file && !promotion) return supported(Map.of());
        if (executorVersion == null || executorVersion.isBlank()) {
            // Package-level unit callers may intentionally omit a version. The
            // Maven integration always supplies the selected plugin version.
            return supported(Map.of());
        }
        if (executorVersion.contains("${")) {
            return Result.unsupported("executor version is unresolved; property-parameter capability cannot be proven");
        }

        ComparableVersion version = new ComparableVersion(executorVersion.trim());
        if (variables && version.compareTo(SYSTEM_PROPERTY_VARIABLES_SINCE) < 0) {
            return Result.unsupported("<systemPropertyVariables> requires Surefire/Failsafe 2.5 or newer; selected version is "
                    + executorVersion);
        }
        if (file && version.compareTo(SYSTEM_PROPERTIES_FILE_SINCE) < 0) {
            return Result.unsupported("<systemPropertiesFile> requires Surefire/Failsafe 2.8.2 or newer; selected version is "
                    + executorVersion);
        }
        if (promotion && version.compareTo(PROMOTION_TOGGLE_SINCE) < 0) {
            return Result.unsupported("<promoteUserPropertiesToSystemProperties> requires Surefire/Failsafe 3.4.0 or newer; selected version is "
                    + executorVersion);
        }
        return supported(Map.of());
    }

    private boolean containsMeaningful(List<Xpp3Dom> configurations, String name) {
        for (Xpp3Dom configuration : configurations) {
            Xpp3Dom child = configuration.getChild(name);
            if (child != null && meaningful(child)) return true;
        }
        return false;
    }

    private Result readLegacySystemProperties(Xpp3Dom parent,
                                               Function<String, String> resolver,
                                               Map<String, Value> target) {
        for (Xpp3Dom property : parent.getChildren()) {
            if (!"property".equals(property.getName())) {
                return Result.unsupported("<systemProperties> contains unsupported child <" + property.getName() + ">");
            }
            Xpp3Dom name = property.getChild("name");
            Xpp3Dom value = property.getChild("value");
            if (name == null || value == null || property.getChildCount() != 2
                    || name.getChildCount() > 0 || value.getChildCount() > 0) {
                return Result.unsupported("<systemProperties><property> must contain exactly scalar <name> and <value>");
            }
            String key = resolve(name.getValue(), resolver);
            String resolvedValue = resolve(value.getValue(), resolver);
            if (key == null || resolvedValue == null || key.isBlank()) {
                return Result.unsupported("legacy <systemProperties> contains an unresolved or blank property");
            }
            target.put(key, new Value(resolvedValue, Origin.LEGACY_SYSTEM_PROPERTIES));
        }
        return supported(target);
    }

    private Result readSystemPropertiesFile(Xpp3Dom node,
                                            Path projectBaseDirectory,
                                            Function<String, String> resolver,
                                            Map<String, Value> target) {
        if (node.getChildCount() > 0) {
            return Result.unsupported("structured <systemPropertiesFile> cannot be reproduced safely");
        }
        String configured = resolve(node.getValue(), resolver);
        if (configured == null || configured.isBlank()) {
            return Result.unsupported("<systemPropertiesFile> must resolve to a non-blank path");
        }
        if (projectBaseDirectory == null) {
            return Result.unsupported("<systemPropertiesFile> requires Maven project.basedir for exact relative-path resolution");
        }

        final Path path;
        try {
            Path candidate = Path.of(configured);
            path = candidate.isAbsolute() ? candidate.normalize() : projectBaseDirectory.resolve(candidate).normalize();
        } catch (RuntimeException invalidPath) {
            return Result.unsupported("<systemPropertiesFile> resolves to an invalid path: " + safeMessage(invalidPath));
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return Result.unsupported("<systemPropertiesFile> does not exist or is not a readable regular file: " + path);
        }

        Properties loaded = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) {
            loaded.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return Result.unsupported("<systemPropertiesFile> cannot be loaded with Java Properties semantics: "
                    + safeMessage(exception));
        }
        for (Map.Entry<Object, Object> entry : loaded.entrySet()) {
            target.put(String.valueOf(entry.getKey()),
                    new Value(String.valueOf(entry.getValue()), Origin.SYSTEM_PROPERTIES_FILE));
        }
        return supported(target);
    }

    private Result readSystemPropertyVariables(Xpp3Dom parent,
                                                Function<String, String> resolver,
                                                Map<String, Value> target) {
        for (Xpp3Dom property : parent.getChildren()) {
            if (property.getChildCount() > 0) {
                return Result.unsupported("<systemPropertyVariables> contains nested property '" + property.getName() + "'");
            }
            String resolved = resolve(property.getValue(), resolver);
            if (resolved == null) {
                return Result.unsupported("system property '" + property.getName() + "' contains an unresolved Maven property");
            }
            target.put(property.getName(), new Value(resolved, Origin.SYSTEM_PROPERTY_VARIABLES));
        }
        return supported(target);
    }

    private Boolean readPromotionFlag(Xpp3Dom node, Function<String, String> resolver) {
        if (node.getChildCount() > 0) return null;
        String resolved = resolve(node.getValue(), resolver);
        if (resolved == null) return null;
        if (resolved.isBlank()) return Boolean.TRUE;
        if ("true".equalsIgnoreCase(resolved)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(resolved)) return Boolean.FALSE;
        return null;
    }

    private Result supported(Map<String, Value> values) {
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        LinkedHashMap<String, Origin> origins = new LinkedHashMap<>();
        LinkedHashSet<String> startupOnly = new LinkedHashSet<>();
        values.forEach((key, value) -> {
            properties.put(key, value.value());
            origins.put(key, value.origin());
            if (VM_STARTUP_ONLY.contains(key)) startupOnly.add(key);
        });
        return new Result(true,
                Collections.unmodifiableMap(properties),
                Collections.unmodifiableMap(origins),
                Collections.unmodifiableSet(startupOnly),
                "effective executor system properties reproduced");
    }

    private String resolve(String raw, Function<String, String> resolver) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        Matcher matcher = PROPERTY_REFERENCE.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String replacement = resolver.apply(matcher.group(1));
            if (replacement == null) return null;
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private boolean meaningful(Xpp3Dom node) {
        if (node.getChildCount() > 0) return true;
        String value = node.getValue();
        String[] attributes = node.getAttributeNames();
        return (value != null && !value.isBlank()) || (attributes != null && attributes.length > 0);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ');
    }
}
