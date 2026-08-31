package io.scenariomesh.maven.extension;

import io.scenariomesh.config.ConfigResolver;
import io.scenariomesh.config.ConfigResolver.ConfigResolution;
import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.core.MavenOwnershipDiagnostic;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "scenariomesh")
public final class ScenarioMeshLifecycleParticipant extends AbstractMavenLifecycleParticipant {
    private static final String GROUP_ID = "io.scenariomesh";
    private static final String PLUGIN_ARTIFACT_ID = "scenariomesh-maven-plugin";
    private static final String VERSION = "0.1.0-SNAPSHOT";
    private static final String PREFLIGHT_EXECUTION_ID = "scenariomesh-preflight";
    private static final String RUN_EXECUTION_ID = "scenariomesh-run";
    private static final String VERIFY_EXECUTION_ID = "scenariomesh-verify";
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
    private static final String FAILSAFE = "org.apache.maven.plugins:maven-failsafe-plugin";

    private final ProjectCompatibilityDetector compatibilityDetector = new ProjectCompatibilityDetector();
    private final AdvancedSurefireCompatibilityDetector advancedSurefireCompatibilityDetector = new AdvancedSurefireCompatibilityDetector();
    private final MavenForkLaunchConfiguration forkLaunchConfiguration = new MavenForkLaunchConfiguration();
    private final MavenExecutorClasspathConfiguration executorClasspathConfiguration = new MavenExecutorClasspathConfiguration();
    private final MavenRunOrderConfiguration runOrderConfiguration = new MavenRunOrderConfiguration();
    private final MavenProviderDependencyCompatibility providerDependencyCompatibility = new MavenProviderDependencyCompatibility();
    private final DownstreamReportCompatibility downstreamReportCompatibility = new DownstreamReportCompatibility();
    private final DownstreamLifecycleCompatibility downstreamLifecycleCompatibility = new DownstreamLifecycleCompatibility();
    private final ConfigResolver configResolver = new ConfigResolver();
    @Requirement private Logger logger;
    @Requirement private RepositorySystem repositorySystem;

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        if (booleanProperty(session, "skipTests") || booleanProperty(session, "maven.test.skip")) {
            for (MavenProject project : session.getProjects()) {
                if (!"pom".equals(project.getPackaging())) {
                    diagnostic(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project,
                            "none", "none", "tests were explicitly skipped by Maven");
                }
            }
            return;
        }
        Map<String, String> configProperties = stringProperties(session.getSystemProperties());
        configProperties.putAll(stringProperties(session.getUserProperties()));

        for (MavenProject project : session.getProjects()) {
            if ("pom".equals(project.getPackaging())) continue;
            ScenarioMeshConfig config;
            ConfigResolution resolution;
            try {
                Path projectDirectory = project.getBasedir().toPath().toAbsolutePath().normalize();
                Path buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
                resolution = configResolver.resolveDetailed(projectDirectory, buildDirectory, configProperties, System.getenv());
                config = resolution.config();
            } catch (IllegalArgumentException exception) {
                throw new MavenExecutionException(
                        "ScenarioMesh configuration error for project '" + project.getArtifactId() + "': " + exception.getMessage(), exception);
            }

            if (!config.enabled()) {
                diagnostic(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project,
                        "none", "none", "ScenarioMesh configuration is disabled");
                info("ScenarioMesh: disabled for " + project.getArtifactId() + "; normal Maven execution remains active.");
                continue;
            }

            ProjectCompatibilityDetector.CompatibilityDecision decision = evaluateModelSemantics(session, project);
            Plugin surefire = project.getPlugin(SUREFIRE);
            if (!decision.compatible() && surefire != null && requiresAdvancedSurefireAnalysis(surefire)) {
                ProjectCompatibilityDetector.CompatibilityDecision advanced = advancedSurefireCompatibilityDetector.evaluate(session, project);
                if (advanced.compatible()) decision = advanced;
            }
            if (!decision.compatible()) {
                diagnostic(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project,
                        "none", "none", decision.reason());
                info("ScenarioMesh: pass-through for " + project.getArtifactId() + " - " + decision.reason());
                continue;
            }

            EffectivePropertyResolver effectiveProperties = new EffectivePropertyResolver(session, project);
            Plugin nativeExecutor = project.getPlugin(
                    decision.executorKind() == ProjectCompatibilityDetector.ExecutorKind.FAILSAFE ? FAILSAFE : SUREFIRE);
            List<String> executionIds = decision.executorPlans().stream()
                    .map(ProjectCompatibilityDetector.ExecutorPlan::executionId).toList();

            MavenProviderDependencyCompatibility.Analysis providerAnalysis = providerDependencyCompatibility.analyze(nativeExecutor);
            if (!providerAnalysis.supported()) {
                diagnosePlans(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project, decision, providerAnalysis.reason());
                info("ScenarioMesh: pass-through for " + project.getArtifactId() + " - " + providerAnalysis.reason());
                continue;
            }

            MavenRunOrderConfiguration.Analysis runOrderAnalysis = runOrderConfiguration.analyze(
                    nativeExecutor, decision.executorKind(), executionIds,
                    effectiveProperties::resolve, effectiveProperties::userProperty);
            if (!runOrderAnalysis.supported()) {
                diagnosePlans(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project, decision,
                        "Maven run order cannot be reproduced safely: " + runOrderAnalysis.reason());
                info("ScenarioMesh: pass-through for " + project.getArtifactId()
                        + " - Maven run order cannot be reproduced safely: " + runOrderAnalysis.reason());
                continue;
            }

            MavenForkLaunchConfiguration.Analysis launchAnalysis = forkLaunchConfiguration.analyze(
                    nativeExecutor, decision.executorKind(), executionIds,
                    effectiveProperties::resolve, effectiveProperties::userProperty);
            if (!launchAnalysis.supported()) {
                diagnosePlans(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project, decision,
                        "Maven fork launch configuration cannot be reproduced safely: " + launchAnalysis.reason());
                info("ScenarioMesh: pass-through for " + project.getArtifactId()
                        + " - Maven fork launch configuration cannot be reproduced safely: " + launchAnalysis.reason());
                continue;
            }

            MavenAdditionalClasspathDependencyResolver dependencyResolver =
                    new MavenAdditionalClasspathDependencyResolver(repositorySystem);
            MavenExecutorClasspathConfiguration.Analysis classpathAnalysis = executorClasspathConfiguration.analyze(
                    nativeExecutor, decision.executorKind(), executionIds,
                    effectiveProperties::resolve, effectiveProperties::userProperty,
                    dependencies -> dependencyResolver.resolve(session, project, dependencies));
            if (!classpathAnalysis.supported()) {
                diagnosePlans(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project, decision,
                        "Maven executor classpath cannot be reproduced safely: " + classpathAnalysis.reason());
                info("ScenarioMesh: pass-through for " + project.getArtifactId()
                        + " - Maven executor classpath cannot be reproduced safely: " + classpathAnalysis.reason());
                continue;
            }

            List<String> providerClasspath;
            try {
                providerClasspath = dependencyResolver.resolve(session, project, providerAnalysis.engineDependencies());
            } catch (Exception exception) {
                String reason = "recognized JUnit Platform engine plugin dependencies could not be resolved exactly: " + exception.getMessage();
                diagnosePlans(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project, decision, reason);
                info("ScenarioMesh: pass-through for " + project.getArtifactId() + " - " + reason);
                continue;
            }

            DownstreamLifecycleCompatibility.Analysis lifecycleAnalysis =
                    downstreamLifecycleCompatibility.analyze(project, decision.executorKind());
            if (!lifecycleAnalysis.supported()) {
                diagnosePlans(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project, decision, lifecycleAnalysis.reason());
                info("ScenarioMesh: pass-through for " + project.getArtifactId() + " - " + lifecycleAnalysis.reason());
                continue;
            }

            String reportInvocationId = UUID.randomUUID().toString();
            DownstreamReportCompatibility.Analysis reportAnalysis = downstreamReportCompatibility.prepare(project, reportInvocationId);
            if (reportAnalysis.present() && !reportAnalysis.supported()) {
                diagnosePlans(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project, decision, reportAnalysis.reason());
                info("ScenarioMesh: pass-through for " + project.getArtifactId() + " - " + reportAnalysis.reason());
                continue;
            }
            if (reportAnalysis.present() && decision.executorPlans().size() > 1) {
                String reason = "multiple Maven test executions are reproducible, but the detected downstream report consumer "
                        + "has a single mutable input directory; ScenarioMesh will not merge distinct execution reports implicitly";
                diagnosePlans(MavenOwnershipDiagnostic.Owner.PASS_THROUGH, project, decision, reason);
                info("ScenarioMesh: pass-through for " + project.getArtifactId() + " - " + reason + ".");
                continue;
            }

            injectScenarioMesh(project, decision, launchAnalysis, classpathAnalysis, runOrderAnalysis,
                    providerClasspath, reportInvocationId, reportAnalysis.runtimeProperties());
            String configText = resolution.configFile().map(path -> ", config=" + path).orElse("");
            info("ScenarioMesh: takeover candidate for " + project.getArtifactId()
                    + " (executor=" + decision.executorKind().name().toLowerCase()
                    + ", phase=" + decision.takeoverPhase()
                    + ", executionPlans=" + decision.executorPlans().size()
                    + ", signals=" + (decision.frameworks().isEmpty() ? "none; runtime detection required" : String.join(", ", decision.frameworks()))
                    + ", adapterIntent=" + config.executionAdapter() + configText
                    + "); runtime ownership will be proven after test compilation.");
        }
    }

    private ProjectCompatibilityDetector.CompatibilityDecision evaluateModelSemantics(
            MavenSession session, MavenProject project) {
        Plugin surefire = project.getPlugin(SUREFIRE);
        Plugin failsafe = project.getPlugin(FAILSAFE);
        List<Dependency> surefireDependencies = surefire == null ? null : surefire.getDependencies();
        List<Dependency> failsafeDependencies = failsafe == null ? null : failsafe.getDependencies();
        boolean clearSurefire = surefire != null && providerDependencyCompatibility.analyze(surefire).supported()
                && surefireDependencies != null && !surefireDependencies.isEmpty();
        boolean clearFailsafe = failsafe != null && providerDependencyCompatibility.analyze(failsafe).supported()
                && failsafeDependencies != null && !failsafeDependencies.isEmpty();
        try {
            if (clearSurefire) surefire.setDependencies(new ArrayList<>());
            if (clearFailsafe) failsafe.setDependencies(new ArrayList<>());
            return compatibilityDetector.evaluate(session, project);
        } finally {
            if (clearSurefire) surefire.setDependencies(surefireDependencies);
            if (clearFailsafe) failsafe.setDependencies(failsafeDependencies);
        }
    }

    private boolean requiresAdvancedSurefireAnalysis(Plugin surefire) {
        if (surefire.getDependencies() != null && !surefire.getDependencies().isEmpty()) return true;
        if (surefire.getExecutions() == null) return false;
        long tests = surefire.getExecutions().stream()
                .filter(execution -> execution.getGoals() != null && execution.getGoals().contains("test")).count();
        return tests > 1 || surefire.getExecutions().stream()
                .anyMatch(execution -> execution.getGoals() != null && execution.getGoals().contains("test")
                        && !"default-test".equals(execution.getId()));
    }

    private void injectScenarioMesh(
            MavenProject project,
            ProjectCompatibilityDetector.CompatibilityDecision decision,
            MavenForkLaunchConfiguration.Analysis launchAnalysis,
            MavenExecutorClasspathConfiguration.Analysis classpathAnalysis,
            MavenRunOrderConfiguration.Analysis runOrderAnalysis,
            List<String> providerClasspath,
            String singlePlanInvocationId,
            Map<String, String> downstreamRuntimeProperties) {
        Plugin plugin = project.getPlugin(GROUP_ID + ":" + PLUGIN_ARTIFACT_ID);
        if (plugin == null) {
            plugin = new Plugin();
            plugin.setGroupId(GROUP_ID);
            plugin.setArtifactId(PLUGIN_ARTIFACT_ID);
            plugin.setVersion(VERSION);
            project.getBuild().addPlugin(plugin);
        }
        plugin.getExecutions().removeIf(execution -> isManagedExecutionId(execution.getId()));

        PluginExecution preflight = new PluginExecution();
        preflight.setId(PREFLIGHT_EXECUTION_ID);
        preflight.setPhase("process-test-classes");
        preflight.addGoal("preflight");
        Xpp3Dom preflightConfig = new Xpp3Dom("configuration");
        addValue(preflightConfig, "takeoverExecutor", decision.executorKind().name().toLowerCase());
        addValue(preflightConfig, "knownModelFramework", Boolean.toString(!decision.frameworks().isEmpty()));
        addList(preflightConfig, "takeoverExecutionIds", "executionId", decision.executorPlans().stream()
                .map(ProjectCompatibilityDetector.ExecutorPlan::executionId).toList());
        if (decision.executorPlans().size() == 1) {
            ProjectCompatibilityDetector.ExecutorPlan plan = decision.executorPlans().get(0);
            addList(preflightConfig, "includeClassNameRegexes", "include", plan.includeClassNameRegexes());
            addList(preflightConfig, "excludeClassNameRegexes", "exclude", plan.excludeClassNameRegexes());
            addMap(preflightConfig, "executorSystemProperties",
                    mergedProperties(plan, runOrderAnalysis.required(plan.executionId()), Map.of()));
            addLaunchConfiguration(preflightConfig, launchAnalysis.required(plan.executionId()));
            addClasspathConfiguration(preflightConfig, classpathAnalysis.required(plan.executionId()), providerClasspath);
        }
        preflight.setConfiguration(preflightConfig);
        plugin.addExecution(preflight);

        boolean single = decision.executorPlans().size() == 1;
        for (int index = 0; index < decision.executorPlans().size(); index++) {
            ProjectCompatibilityDetector.ExecutorPlan plan = decision.executorPlans().get(index);
            String suffix = single ? "" : "-" + (index + 1) + "-" + safeExecutionId(plan.executionId());
            String invocationId = single ? singlePlanInvocationId : UUID.randomUUID().toString();
            PluginExecution run = new PluginExecution();
            run.setId(RUN_EXECUTION_ID + suffix);
            run.setPhase(decision.takeoverPhase());
            run.addGoal("run");
            run.setConfiguration(runConfiguration(decision, plan,
                    launchAnalysis.required(plan.executionId()),
                    classpathAnalysis.required(plan.executionId()),
                    runOrderAnalysis.required(plan.executionId()), providerClasspath,
                    invocationId, single ? downstreamRuntimeProperties : Map.of()));
            plugin.addExecution(run);

            if (decision.deferFailureUntilVerify()) {
                PluginExecution verify = new PluginExecution();
                verify.setId(VERIFY_EXECUTION_ID + suffix);
                verify.setPhase("verify");
                verify.addGoal("verify");
                Xpp3Dom verifyConfig = new Xpp3Dom("configuration");
                addValue(verifyConfig, "invocationId", invocationId);
                verify.setConfiguration(verifyConfig);
                plugin.addExecution(verify);
            }
        }
    }

    private Map<String, String> mergedProperties(ProjectCompatibilityDetector.ExecutorPlan plan,
                                                  MavenRunOrderConfiguration.Settings runOrder,
                                                  Map<String, String> downstream) {
        Map<String, String> values = new LinkedHashMap<>(plan.executorSystemProperties());
        values.putAll(runOrder.internalProperties());
        values.putAll(downstream);
        return Map.copyOf(values);
    }

    private boolean isManagedExecutionId(String id) {
        return id != null && (id.equals(PREFLIGHT_EXECUTION_ID)
                || id.equals(RUN_EXECUTION_ID) || id.startsWith(RUN_EXECUTION_ID + "-")
                || id.equals(VERIFY_EXECUTION_ID) || id.startsWith(VERIFY_EXECUTION_ID + "-"));
    }

    private String safeExecutionId(String value) {
        if (value == null || value.isBlank()) return "unnamed";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Xpp3Dom runConfiguration(ProjectCompatibilityDetector.CompatibilityDecision decision,
                                     ProjectCompatibilityDetector.ExecutorPlan plan,
                                     MavenForkLaunchConfiguration.LaunchSettings launchSettings,
                                     MavenExecutorClasspathConfiguration.Settings classpathSettings,
                                     MavenRunOrderConfiguration.Settings runOrderSettings,
                                     List<String> providerClasspath,
                                     String invocationId,
                                     Map<String, String> downstreamRuntimeProperties) {
        Xpp3Dom root = new Xpp3Dom("configuration");
        addValue(root, "invocationId", invocationId);
        addValue(root, "deferFailureUntilVerify", Boolean.toString(decision.deferFailureUntilVerify()));
        addValue(root, "takeoverExecutor", decision.executorKind().name().toLowerCase());
        addValue(root, "testFailureIgnore", Boolean.toString(plan.testFailureIgnore()));
        addList(root, "includeClassNameRegexes", "include", plan.includeClassNameRegexes());
        addList(root, "excludeClassNameRegexes", "exclude", plan.excludeClassNameRegexes());
        addList(root, "executorJvmArgs", "arg", plan.executorJvmArgs());
        addMap(root, "executorSystemProperties", mergedProperties(plan, runOrderSettings, downstreamRuntimeProperties));
        addLaunchConfiguration(root, launchSettings);
        addClasspathConfiguration(root, classpathSettings, providerClasspath);
        return root;
    }

    private void addLaunchConfiguration(Xpp3Dom root, MavenForkLaunchConfiguration.LaunchSettings settings) {
        addValue(root, "enableAssertions", Boolean.toString(settings.enableAssertions()));
        if (!settings.environmentVariables().isEmpty()) {
            List<String> entries = settings.environmentVariables().entrySet().stream()
                    .map(entry -> encode(entry.getKey()) + ":" + encode(entry.getValue())).toList();
            addList(root, "executorEnvironmentEntries", "entry", entries);
        }
        addList(root, "excludedEnvironmentVariables", "name", settings.excludedEnvironmentVariables().stream().toList());
        if (settings.workingDirectory() != null) addValue(root, "executorWorkingDirectory", settings.workingDirectory().toString());
    }

    private void addClasspathConfiguration(Xpp3Dom root, MavenExecutorClasspathConfiguration.Settings settings,
                                           List<String> providerClasspath) {
        LinkedHashSet<String> additional = new LinkedHashSet<>(settings.additionalClasspathElements());
        additional.addAll(providerClasspath == null ? List.of() : providerClasspath);
        addList(root, "additionalClasspathElements", "element", new ArrayList<>(additional));
        addList(root, "classpathDependencyExcludes", "exclude", settings.classpathDependencyExcludes());
        if (settings.classpathDependencyScopeExclude() != null) {
            addValue(root, "classpathDependencyScopeExclude", settings.classpathDependencyScopeExclude());
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void addValue(Xpp3Dom root, String name, String value) {
        Xpp3Dom node = new Xpp3Dom(name);
        node.setValue(value);
        root.addChild(node);
    }

    private void addList(Xpp3Dom root, String name, String itemName, List<String> values) {
        if (values == null || values.isEmpty()) return;
        Xpp3Dom list = new Xpp3Dom(name);
        for (String value : values) {
            Xpp3Dom item = new Xpp3Dom(itemName);
            item.setValue(value);
            list.addChild(item);
        }
        root.addChild(list);
    }

    private void addMap(Xpp3Dom root, String name, Map<String, String> values) {
        if (values == null || values.isEmpty()) return;
        Xpp3Dom map = new Xpp3Dom(name);
        values.forEach((key, value) -> {
            Xpp3Dom item = new Xpp3Dom(key);
            item.setValue(value);
            map.addChild(item);
        });
        root.addChild(map);
    }

    private void diagnosePlans(MavenOwnershipDiagnostic.Owner owner, MavenProject project,
                               ProjectCompatibilityDetector.CompatibilityDecision decision, String reason) {
        if (decision.executorPlans().isEmpty()) {
            diagnostic(owner, project, decision.executorKind().name().toLowerCase(), "none", reason);
            return;
        }
        for (ProjectCompatibilityDetector.ExecutorPlan plan : decision.executorPlans()) {
            diagnostic(owner, project, decision.executorKind().name().toLowerCase(), plan.executionId(), reason);
        }
    }

    private void diagnostic(MavenOwnershipDiagnostic.Owner owner, MavenProject project,
                            String executor, String execution, String reason) {
        info(MavenOwnershipDiagnostic.format(owner, project.getArtifactId(), executor, execution, reason));
    }

    private boolean booleanProperty(MavenSession session, String key) {
        String value = session.getUserProperties().getProperty(key);
        if (value == null) value = session.getSystemProperties().getProperty(key);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private Map<String, String> stringProperties(java.util.Properties properties) {
        Map<String, String> values = new LinkedHashMap<>();
        if (properties != null) properties.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        return values;
    }

    private void info(String message) {
        if (logger != null) logger.info(message);
        else System.out.println("[INFO] " + message);
    }
}
