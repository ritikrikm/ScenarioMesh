package io.scenariomesh.maven.extension;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Models native Surefire/Failsafe test-classpath mutations independently for each execution. */
final class MavenExecutorClasspathConfiguration {
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    Analysis analyze(Plugin plugin,
                     ProjectCompatibilityDetector.ExecutorKind executorKind,
                     List<String> executionIds,
                     Function<String, String> propertyResolver,
                     Function<String, String> userPropertyResolver,
                     AdditionalDependencyResolver additionalDependencyResolver) {
        List<String> reasons = new ArrayList<>();
        Map<String, Settings> byExecution = new LinkedHashMap<>();
        String executor = executorKind == ProjectCompatibilityDetector.ExecutorKind.FAILSAFE ? "failsafe" : "surefire";

        List<String> ids = executorKind == ProjectCompatibilityDetector.ExecutorKind.SUREFIRE
                ? List.of("default-test") : executionIds;
        for (String executionId : ids) {
            MutableSettings settings = new MutableSettings();
            if (plugin != null) {
                inspect(plugin.getConfiguration(), "maven-" + executor + "-plugin configuration",
                        settings, reasons, propertyResolver);
                PluginExecution execution = findExecution(plugin, executionId);
                if (execution != null) {
                    inspect(execution.getConfiguration(), "maven-" + executor + "-plugin execution '" + executionId + "'",
                            settings, reasons, propertyResolver);
                }
            }
            applyUserOverrides(settings, reasons, userPropertyResolver);
            Settings frozen = settings.freeze();
            if (reasons.isEmpty() && !frozen.additionalClasspathDependencies().isEmpty()) {
                try {
                    frozen = frozen.withResolvedAdditionalDependencies(
                            additionalDependencyResolver.resolve(frozen.additionalClasspathDependencies()));
                } catch (Exception exception) {
                    reasons.add("could not resolve <additionalClasspathDependencies> with Surefire-equivalent Maven Resolver semantics: "
                            + safeMessage(exception));
                }
            }
            byExecution.put(executionId, frozen);
        }
        return reasons.isEmpty() ? Analysis.supported(byExecution) : Analysis.unsupported(String.join("; ", reasons));
    }

    private void inspect(Object raw, String location, MutableSettings settings, List<String> reasons,
                         Function<String, String> propertyResolver) {
        if (!(raw instanceof Xpp3Dom configuration)) return;
        for (Xpp3Dom child : configuration.getChildren()) {
            if (!meaningful(child)) continue;
            switch (child.getName()) {
                case "additionalClasspathElements" -> readAdditionalElements(child, location, settings, reasons, propertyResolver);
                case "classpathDependencyExcludes" -> readDependencyExcludes(child, location, settings, reasons, propertyResolver);
                case "classpathDependencyScopeExclude" -> readScopeExclude(child, location, settings, reasons, propertyResolver);
                case "additionalClasspathDependencies" -> readAdditionalDependencies(child, location, settings, reasons, propertyResolver);
                default -> { }
            }
        }
    }

    private void readAdditionalElements(Xpp3Dom parent, String location, MutableSettings settings,
                                        List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"additionalClasspathElement".equals(item.getName()) || item.getChildCount() > 0) {
                reasons.add(location + " contains unsupported structure inside <additionalClasspathElements>");
                continue;
            }
            String value = resolve(item.getValue(), location + " <additionalClasspathElement>", reasons, propertyResolver);
            if (value == null || value.isBlank()) {
                reasons.add(location + " contains a blank <additionalClasspathElement>");
                continue;
            }
            try {
                Path path = Path.of(value);
                if (!path.isAbsolute()) {
                    reasons.add(location + " contains relative <additionalClasspathElement> '" + value
                            + "'; native Surefire treats these values as filesystem paths after Maven conversion, so ScenarioMesh requires an absolute resolved value");
                } else settings.additionalClasspathElements.add(path.normalize().toString());
            } catch (RuntimeException invalid) {
                reasons.add(location + " contains an invalid <additionalClasspathElement> path");
            }
        }
    }

    private void readAdditionalDependencies(Xpp3Dom parent, String location, MutableSettings settings,
                                            List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"additionalClasspathDependency".equals(item.getName())) {
                reasons.add(location + " contains unsupported element <" + item.getName()
                        + "> inside <additionalClasspathDependencies>");
                continue;
            }
            Dependency dependency = new Dependency();
            boolean malformed = false;
            for (Xpp3Dom field : item.getChildren()) {
                switch (field.getName()) {
                    case "groupId" -> dependency.setGroupId(simpleValue(field, location, "groupId", reasons, propertyResolver));
                    case "artifactId" -> dependency.setArtifactId(simpleValue(field, location, "artifactId", reasons, propertyResolver));
                    case "version" -> dependency.setVersion(simpleValue(field, location, "version", reasons, propertyResolver));
                    case "type" -> dependency.setType(simpleValue(field, location, "type", reasons, propertyResolver));
                    case "classifier" -> dependency.setClassifier(simpleValue(field, location, "classifier", reasons, propertyResolver));
                    case "scope" -> dependency.setScope(simpleValue(field, location, "scope", reasons, propertyResolver));
                    case "optional" -> dependency.setOptional(simpleValue(field, location, "optional", reasons, propertyResolver));
                    case "exclusions" -> readExclusions(field, dependency, location, reasons, propertyResolver);
                    default -> {
                        reasons.add(location + " uses unsupported <additionalClasspathDependency> field <" + field.getName() + ">");
                        malformed = true;
                    }
                }
            }
            if (blank(dependency.getGroupId()) || blank(dependency.getArtifactId()) || blank(dependency.getVersion())) {
                reasons.add(location + " <additionalClasspathDependency> requires groupId, artifactId and version; project dependency-management is intentionally not applied");
                malformed = true;
            }
            String scope = dependency.getScope();
            if (!blank(scope) && !Set.of("compile", "runtime").contains(scope)) {
                reasons.add(location + " <additionalClasspathDependency> scope '" + scope
                        + "' is outside Surefire's effective compile/runtime additional classpath");
                malformed = true;
            }
            if (!malformed) settings.additionalClasspathDependencies.add(dependency);
        }
    }

    private void readExclusions(Xpp3Dom parent, Dependency dependency, String location, List<String> reasons,
                                Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"exclusion".equals(item.getName())) {
                reasons.add(location + " contains unsupported <" + item.getName() + "> inside dependency exclusions");
                continue;
            }
            Exclusion exclusion = new Exclusion();
            for (Xpp3Dom field : item.getChildren()) {
                if ("groupId".equals(field.getName())) {
                    exclusion.setGroupId(simpleValue(field, location, "exclusion.groupId", reasons, propertyResolver));
                } else if ("artifactId".equals(field.getName())) {
                    exclusion.setArtifactId(simpleValue(field, location, "exclusion.artifactId", reasons, propertyResolver));
                } else {
                    reasons.add(location + " uses unsupported exclusion field <" + field.getName() + ">");
                }
            }
            if (blank(exclusion.getGroupId()) || blank(exclusion.getArtifactId())) {
                reasons.add(location + " dependency exclusion requires groupId and artifactId");
            } else dependency.addExclusion(exclusion);
        }
    }

    private String simpleValue(Xpp3Dom node, String location, String field, List<String> reasons,
                               Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " uses structured value for " + field);
            return null;
        }
        String value = resolve(node.getValue(), location + " " + field, reasons, propertyResolver);
        return value == null ? null : value.trim();
    }

    private void readDependencyExcludes(Xpp3Dom parent, String location, MutableSettings settings,
                                        List<String> reasons, Function<String, String> propertyResolver) {
        for (Xpp3Dom item : parent.getChildren()) {
            if (!"classpathDependencyExclude".equals(item.getName()) || item.getChildCount() > 0) {
                reasons.add(location + " contains unsupported structure inside <classpathDependencyExcludes>");
                continue;
            }
            String value = resolve(item.getValue(), location + " <classpathDependencyExclude>", reasons, propertyResolver);
            if (value == null || value.isBlank()) reasons.add(location + " contains a blank classpath dependency exclusion");
            else settings.classpathDependencyExcludes.add(value.trim());
        }
    }

    private void readScopeExclude(Xpp3Dom node, String location, MutableSettings settings,
                                  List<String> reasons, Function<String, String> propertyResolver) {
        if (node.getChildCount() > 0) {
            reasons.add(location + " uses structured <classpathDependencyScopeExclude>");
            return;
        }
        String value = resolve(node.getValue(), location + " <classpathDependencyScopeExclude>", reasons, propertyResolver);
        if (value == null) return;
        String scope = value.trim();
        if (scope.isEmpty()) settings.classpathDependencyScopeExclude = null;
        else if (Set.of("compile", "runtime", "compile+runtime", "runtime+system", "test").contains(scope)) {
            settings.classpathDependencyScopeExclude = scope;
        } else reasons.add(location + " uses unsupported classpath dependency scope exclusion '" + scope + "'");
    }

    private void applyUserOverrides(MutableSettings settings, List<String> reasons,
                                    Function<String, String> userPropertyResolver) {
        String additional = userPropertyResolver.apply("maven.test.additionalClasspath");
        if (additional != null) {
            reasons.add("Maven user property 'maven.test.additionalClasspath' is present; exact Plexus String[] conversion is not yet proven by ScenarioMesh");
        }
        String additionalDependencies = userPropertyResolver.apply("maven.test.additionalClasspathDependencies");
        if (additionalDependencies != null) {
            reasons.add("Maven user property 'maven.test.additionalClasspathDependencies' is present; exact Maven Dependency-list CLI conversion is not yet proven by ScenarioMesh");
        }
        String excludes = userPropertyResolver.apply("maven.test.dependency.excludes");
        if (excludes != null) {
            settings.classpathDependencyExcludes.clear();
            for (String raw : excludes.split(",", -1)) {
                String value = raw.trim();
                if (value.isEmpty()) reasons.add("Maven user property 'maven.test.dependency.excludes' contains an empty pattern");
                else settings.classpathDependencyExcludes.add(value);
            }
        }
    }

    private PluginExecution findExecution(Plugin plugin, String id) {
        if (plugin.getExecutions() == null) return null;
        return plugin.getExecutions().stream().filter(execution -> id.equals(execution.getId())).findFirst().orElse(null);
    }

    private String resolve(String raw, String location, List<String> reasons, Function<String, String> propertyResolver) {
        String value = raw == null ? "" : raw;
        Matcher matcher = PROPERTY_REFERENCE.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String replacement = propertyResolver.apply(matcher.group(1));
            if (replacement == null) {
                reasons.add(location + " references an unresolved Maven property ${" + matcher.group(1) + "}");
                return null;
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private boolean meaningful(Xpp3Dom node) {
        return node.getValue() != null || node.getChildCount() > 0
                || (node.getAttributeNames() != null && node.getAttributeNames().length > 0);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getName() : value;
    }

    @FunctionalInterface
    interface AdditionalDependencyResolver {
        List<String> resolve(List<Dependency> dependencies) throws Exception;
    }

    record Settings(List<String> additionalClasspathElements,
                    List<String> classpathDependencyExcludes,
                    String classpathDependencyScopeExclude,
                    List<Dependency> additionalClasspathDependencies) {
        Settings {
            additionalClasspathElements = List.copyOf(additionalClasspathElements == null ? List.of() : additionalClasspathElements);
            classpathDependencyExcludes = List.copyOf(classpathDependencyExcludes == null ? List.of() : classpathDependencyExcludes);
            additionalClasspathDependencies = List.copyOf(additionalClasspathDependencies == null ? List.of() : additionalClasspathDependencies);
        }
        static Settings defaults() { return new Settings(List.of(), List.of(), null, List.of()); }
        boolean custom() { return !additionalClasspathElements.isEmpty() || !classpathDependencyExcludes.isEmpty()
                || classpathDependencyScopeExclude != null || !additionalClasspathDependencies.isEmpty(); }
        Settings withResolvedAdditionalDependencies(List<String> resolved) {
            LinkedHashSet<String> combined = new LinkedHashSet<>(additionalClasspathElements);
            combined.addAll(resolved == null ? List.of() : resolved);
            return new Settings(List.copyOf(combined), classpathDependencyExcludes,
                    classpathDependencyScopeExclude, additionalClasspathDependencies);
        }
    }

    record Analysis(boolean supported, String reason, Map<String, Settings> byExecutionId) {
        Analysis { byExecutionId = Map.copyOf(byExecutionId == null ? Map.of() : byExecutionId); }
        static Analysis supported(Map<String, Settings> values) { return new Analysis(true, null, values); }
        static Analysis unsupported(String reason) { return new Analysis(false, reason, Map.of()); }
        Settings required(String executionId) {
            Settings value = byExecutionId.get(executionId);
            if (value == null) throw new IllegalStateException("Missing classpath settings for Maven execution '" + executionId + "'");
            return value;
        }
    }

    private static final class MutableSettings {
        private final Set<String> additionalClasspathElements = new LinkedHashSet<>();
        private final Set<String> classpathDependencyExcludes = new LinkedHashSet<>();
        private final List<Dependency> additionalClasspathDependencies = new ArrayList<>();
        private String classpathDependencyScopeExclude;
        Settings freeze() { return new Settings(List.copyOf(additionalClasspathElements),
                List.copyOf(classpathDependencyExcludes), classpathDependencyScopeExclude,
                List.copyOf(additionalClasspathDependencies)); }
    }
}
