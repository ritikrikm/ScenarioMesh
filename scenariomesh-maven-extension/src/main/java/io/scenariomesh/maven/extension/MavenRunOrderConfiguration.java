package io.scenariomesh.maven.extension;

import io.scenariomesh.core.RuntimePropertyNames;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/** Resolves class run-order semantics independently for each Maven test execution. */
final class MavenRunOrderConfiguration {
    private static final Set<String> STATELESS_MODES = Set.of(
            "filesystem", "alphabetical", "reversealphabetical", "random");
    private static final Set<String> STATEFUL_MODES = Set.of("failedfirst", "balanced");

    Analysis analyze(Plugin plugin,
                     ProjectCompatibilityDetector.ExecutorKind kind,
                     List<String> executionIds,
                     Function<String, String> propertyResolver,
                     Function<String, String> userPropertyResolver) {
        String prefix = kind == ProjectCompatibilityDetector.ExecutorKind.FAILSAFE ? "failsafe" : "surefire";
        List<String> reasons = new ArrayList<>();
        Map<String, Settings> values = new LinkedHashMap<>();
        for (String executionId : executionIds) {
            MutableSettings settings = new MutableSettings();
            if (plugin != null) {
                inspect(plugin.getConfiguration(), "maven-" + prefix + "-plugin configuration",
                        settings, reasons, propertyResolver);
                PluginExecution execution = findExecution(plugin, executionId);
                if (execution != null) {
                    inspect(execution.getConfiguration(), "maven-" + prefix + "-plugin execution '" + executionId + "'",
                            settings, reasons, propertyResolver);
                }
            }
            applyUserProperties(prefix, settings, reasons, userPropertyResolver);
            normalize(prefix, settings, reasons, propertyResolver);
            values.put(executionId, settings.freeze());
        }
        return reasons.isEmpty() ? Analysis.supported(values) : Analysis.unsupported(String.join("; ", reasons));
    }

    private void inspect(Object raw,
                         String location,
                         MutableSettings settings,
                         List<String> reasons,
                         Function<String, String> propertyResolver) {
        if (!(raw instanceof Xpp3Dom configuration)) return;
        for (Xpp3Dom child : configuration.getChildren()) {
            switch (child.getName()) {
                case "runOrder" -> settings.mode = scalar(child, location, reasons, propertyResolver);
                case "runOrderRandomSeed" -> settings.randomSeed = longValue(child, location, reasons, propertyResolver);
                case "runOrderStatisticsFileChecksum" -> settings.statisticsChecksum = scalar(child, location, reasons, propertyResolver);
                default -> { }
            }
        }
    }

    private void applyUserProperties(String prefix,
                                     MutableSettings settings,
                                     List<String> reasons,
                                     Function<String, String> userPropertyResolver) {
        String mode = userPropertyResolver.apply(prefix + ".runOrder");
        if (mode != null) settings.mode = mode;
        String seed = userPropertyResolver.apply(prefix + ".runOrder.random.seed");
        if (seed != null) {
            try { settings.randomSeed = Long.parseLong(seed.trim()); }
            catch (NumberFormatException invalid) {
                reasons.add("Maven user property '" + prefix + ".runOrder.random.seed' is not a long: " + seed);
            }
        }
        String checksum = userPropertyResolver.apply(prefix + ".runOrder.statisticsFile.checksum");
        if (checksum != null) settings.statisticsChecksum = checksum;
    }

    private void normalize(String prefix,
                           MutableSettings settings,
                           List<String> reasons,
                           Function<String, String> propertyResolver) {
        String mode = settings.mode == null || settings.mode.isBlank()
                ? "filesystem" : settings.mode.trim().toLowerCase(Locale.ROOT);
        if (!STATELESS_MODES.contains(mode) && !STATEFUL_MODES.contains(mode)) {
            reasons.add("maven-" + prefix + "-plugin uses unsupported runOrder '" + settings.mode + "'");
            return;
        }
        settings.mode = mode;
        if ("random".equals(mode) && settings.randomSeed == null) {
            // Surefire seeds java.util.Random from System.nanoTime() when the user did not provide a seed.
            // Generate and persist one effective seed for this ScenarioMesh execution so the ordering can be reproduced.
            settings.randomSeed = ThreadLocalRandom.current().nextLong();
        }
        if (STATEFUL_MODES.contains(mode)) {
            String checksum = settings.statisticsChecksum == null ? "" : settings.statisticsChecksum.trim();
            if (checksum.isEmpty()) {
                reasons.add("maven-" + prefix + "-plugin runOrder=" + mode
                        + " depends on Surefire's configuration-checksummed .surefire-* statistics file; "
                        + "ScenarioMesh will not guess that checksum. Configure " + prefix
                        + ".runOrder.statisticsFile.checksum explicitly to make the state file identity reproducible");
                return;
            }
            String basedir = propertyResolver.apply("project.basedir");
            if (basedir == null || basedir.isBlank()) {
                reasons.add("maven-" + prefix + "-plugin runOrder=" + mode
                        + " requires project.basedir to resolve its statistics file");
                return;
            }
            try {
                settings.statisticsFile = Path.of(basedir).resolve(".surefire-" + checksum)
                        .toAbsolutePath().normalize().toString();
            } catch (RuntimeException invalid) {
                reasons.add("maven-" + prefix + "-plugin run-order statistics path cannot be resolved safely");
            }
        }
    }

    private String scalar(Xpp3Dom node,
                          String location,
                          List<String> reasons,
                          Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " contains structured <" + node.getName() + ">");
            return null;
        }
        String value = node.getValue();
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String resolved = propertyResolver.apply(trimmed.substring(2, trimmed.length() - 1));
            if (resolved == null) {
                reasons.add(location + " <" + node.getName() + "> references unresolved Maven property " + trimmed);
                return null;
            }
            return resolved;
        }
        return value;
    }

    private Long longValue(Xpp3Dom node,
                           String location,
                           List<String> reasons,
                           Function<String, String> propertyResolver) {
        String value = scalar(node, location, reasons, propertyResolver);
        if (value == null || value.isBlank()) return null;
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException invalid) {
            reasons.add(location + " <" + node.getName() + "> is not a long: " + value);
            return null;
        }
    }

    private PluginExecution findExecution(Plugin plugin, String executionId) {
        if (plugin == null || plugin.getExecutions() == null) return null;
        return plugin.getExecutions().stream()
                .filter(execution -> executionId.equals(execution.getId()))
                .findFirst().orElse(null);
    }

    record Settings(String mode, Long randomSeed, String statisticsFile) {
        Map<String, String> internalProperties() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(RuntimePropertyNames.MAVEN_RUN_ORDER, mode == null ? "filesystem" : mode);
            if (randomSeed != null) {
                values.put(RuntimePropertyNames.MAVEN_RUN_ORDER_RANDOM_SEED, Long.toString(randomSeed));
            }
            if (statisticsFile != null) {
                values.put(RuntimePropertyNames.MAVEN_RUN_ORDER_STATISTICS_FILE, statisticsFile);
            }
            return Map.copyOf(values);
        }
    }

    record Analysis(boolean supported, String reason, Map<String, Settings> byExecutionId) {
        Analysis { byExecutionId = Map.copyOf(byExecutionId == null ? Map.of() : byExecutionId); }
        static Analysis supported(Map<String, Settings> values) { return new Analysis(true, null, values); }
        static Analysis unsupported(String reason) { return new Analysis(false, reason, Map.of()); }
        Settings required(String executionId) {
            Settings settings = byExecutionId.get(executionId);
            if (settings == null) throw new IllegalStateException("Missing run-order settings for Maven execution '" + executionId + "'");
            return settings;
        }
    }

    private static final class MutableSettings {
        private String mode = "filesystem";
        private Long randomSeed;
        private String statisticsChecksum;
        private String statisticsFile;
        Settings freeze() { return new Settings(mode, randomSeed, statisticsFile); }
    }
}
