package io.scenariomesh.adapter.cucumberjunit4;

import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.RuntimePropertyNames;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Makes legacy Cucumber JSON reporting parallel-safe by giving each ScenarioMesh
 * execution its own JSON file inside the downstream report consumer's directory.
 */
final class CucumberJsonReportIsolation {
    private static final String MODERN_PLUGIN_PROPERTY = "cucumber.plugin";
    private static final String LEGACY_OPTIONS_PROPERTY = "cucumber.options";

    <T> T execute(
            ScenarioTask task,
            ExecutionContext context,
            Class<?> runnerClass,
            Callable<T> action) throws Exception {
        String directory = context.properties().get(RuntimePropertyNames.CLUECUMBER_JSON_DIRECTORY);
        if (directory == null || directory.isBlank()) {
            return action.call();
        }

        Path reportDirectory = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(reportDirectory);
        Path jsonFile = reportDirectory.resolve(fileName(task, context));

        String previousPlugin = System.getProperty(MODERN_PLUGIN_PROPERTY);
        String previousOptions = System.getProperty(LEGACY_OPTIONS_PROPERTY);
        boolean legacy = usesLegacyCucumberApi(runnerClass);
        try {
            if (legacy) {
                System.setProperty(LEGACY_OPTIONS_PROPERTY, legacyOptionsValue(previousOptions, jsonFile));
            } else {
                List<String> declared = declaredPlugins(runnerClass);
                System.setProperty(MODERN_PLUGIN_PROPERTY, modernPluginValue(declared, previousPlugin, jsonFile));
            }
            return action.call();
        } finally {
            restore(MODERN_PLUGIN_PROPERTY, previousPlugin);
            restore(LEGACY_OPTIONS_PROPERTY, previousOptions);
        }
    }

    static String modernPluginValue(List<String> declaredPlugins, String existingProperty, Path jsonFile) {
        Set<String> plugins = new LinkedHashSet<>();
        addPlugins(plugins, declaredPlugins);
        if (existingProperty != null && !existingProperty.isBlank()) {
            addPlugins(plugins, List.of(existingProperty.split(",")));
        }
        plugins.removeIf(CucumberJsonReportIsolation::isJsonPlugin);
        plugins.add("json:" + jsonFile.toAbsolutePath().normalize());
        return String.join(",", plugins);
    }

    static String legacyOptionsValue(String existingOptions, Path jsonFile) {
        String json = "--plugin json:" + jsonFile.toAbsolutePath().normalize();
        if (existingOptions == null || existingOptions.isBlank()) {
            return json;
        }
        return existingOptions.trim() + " " + json;
    }

    private static void addPlugins(Set<String> target, List<String> values) {
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) target.add(trimmed);
        }
    }

    private static boolean isJsonPlugin(String plugin) {
        return plugin.regionMatches(true, 0, "json:", 0, 5);
    }

    private String fileName(ScenarioTask task, ExecutionContext context) {
        return "scenariomesh-"
                + safe(context.workerId().value()) + "-"
                + safe(task.id().value()) + "-attempt-"
                + context.attempt() + ".json";
    }

    private String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean usesLegacyCucumberApi(Class<?> runnerClass) {
        return annotation(runnerClass, "cucumber.api.CucumberOptions") != null
                && annotation(runnerClass, "io.cucumber.junit.CucumberOptions") == null;
    }

    private List<String> declaredPlugins(Class<?> runnerClass) {
        List<String> plugins = new ArrayList<>();
        Annotation modern = annotation(runnerClass, "io.cucumber.junit.CucumberOptions");
        if (modern != null) {
            addAnnotationStrings(plugins, modern, "plugin");
            return List.copyOf(plugins);
        }
        Annotation legacy = annotation(runnerClass, "cucumber.api.CucumberOptions");
        if (legacy != null) {
            addAnnotationStrings(plugins, legacy, "plugin");
            addAnnotationStrings(plugins, legacy, "format");
        }
        return List.copyOf(plugins);
    }

    @SuppressWarnings("unchecked")
    private Annotation annotation(Class<?> runnerClass, String annotationClassName) {
        try {
            Class<?> raw = Class.forName(annotationClassName, false, runnerClass.getClassLoader());
            if (!Annotation.class.isAssignableFrom(raw)) return null;
            return runnerClass.getAnnotation((Class<? extends Annotation>) raw);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private void addAnnotationStrings(List<String> target, Annotation annotation, String methodName) {
        try {
            Method method = annotation.annotationType().getMethod(methodName);
            Object value = method.invoke(annotation);
            if (value instanceof String[] strings) {
                for (String string : strings) target.add(string);
            }
        } catch (ReflectiveOperationException ignored) {
            // Older CucumberOptions variants do not expose every option name.
        }
    }

    private void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
