package io.scenariomesh.maven.extension;

import io.scenariomesh.core.RuntimePropertyNames;
import io.scenariomesh.maven.selection.MavenSelectionCodec;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCompatibilityDetectorTest {
    private final ProjectCompatibilityDetector detector = new ProjectCompatibilityDetector();

    @Test
    void singleJUnitPlatformOwnerCanTakeOver() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        var decision = detector.evaluate(session("test"), project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals(ProjectCompatibilityDetector.ExecutorKind.SUREFIRE, decision.executorKind());
        assertFalse(decision.includeClassNameRegexes().isEmpty());
    }

    @Test
    void surefireIncludeAndExcludeUserPropertiesAreTranslatedThroughTheProvenPatternParser() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        MavenSession session = session("test");
        session.getUserProperties().setProperty("surefire.includes", "**/*SmokeTest.java,**/*ApiTest.java");
        session.getUserProperties().setProperty("surefire.excludes", "**/*SlowTest.java");
        var decision = detector.evaluate(session, project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals(2, decision.includeClassNameRegexes().size());
        assertEquals(1, decision.excludeClassNameRegexes().size());
        assertNotEquals(SurefireCompatibility.defaultIncludeClassNameRegexes(), decision.includeClassNameRegexes());
    }

    @Test
    void classLevelTestSelectorOverridesConfiguredIncludesAndExcludes() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        MavenSession session = session("test");
        session.getUserProperties().setProperty("surefire.includes", "**/*OtherTest.java");
        session.getUserProperties().setProperty("surefire.excludes", "**/*LoginTest.java");
        session.getUserProperties().setProperty("test", "LoginTest,*Checkout*");
        var decision = detector.evaluate(session, project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals(List.of(".*"), decision.includeClassNameRegexes());
        assertTrue(decision.excludeClassNameRegexes().isEmpty());
        assertEquals("LoginTest,*Checkout*",
                decision.executorSystemProperties().get(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION));
    }

    @Test
    void negatedSurefireIncludePropertyUsesSharedSurefireMatcher() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        MavenSession session = session("test");
        session.getUserProperties().setProperty("surefire.includes", "!Unstable*");
        var decision = detector.evaluate(session, project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals(List.of("!Unstable*"), MavenSelectionCodec.decode(
                decision.executorSystemProperties().get(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS)));
    }

    @Test
    void testExpressionClearsAdvancedIncludeExcludeCollectionsBecauseSurefireTestOverridesThem() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        MavenSession session = session("test");
        session.getUserProperties().setProperty("surefire.includes", "%regex[.*OtherTest.class]");
        session.getUserProperties().setProperty("surefire.excludes", "!NeverMatchedTest");
        session.getUserProperties().setProperty("test", "LoginTest#happy*");
        var decision = detector.evaluate(session, project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals("LoginTest#happy*", decision.executorSystemProperties().get(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION));
        assertFalse(decision.executorSystemProperties().containsKey(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS));
        assertFalse(decision.executorSystemProperties().containsKey(RuntimePropertyNames.MAVEN_EXCLUDED_TEST_PATTERNS));
    }

    @Test
    void methodLevelTestSelectorUsesSharedSurefireMatcher() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        MavenSession session = session("test");
        session.getUserProperties().setProperty("test", "LoginTest#happyPath");
        var decision = detector.evaluate(session, project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals("LoginTest#happyPath", decision.executorSystemProperties().get(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION));
        assertTrue(matchesAny(decision.includeClassNameRegexes(), "example.LoginTest"));
    }

    @Test
    void advancedFailsafeIncludePropertyComposesWithEachExecutionAndUsesSharedMatcher() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        Plugin failsafe = failsafeExecution("integration-tests", "**/ConfiguredIT.java");
        project.getBuild().addPlugin(failsafe);
        MavenSession session = session("verify");
        session.getUserProperties().setProperty("failsafe.includes", "%regex[.*(Smoke|Api)IT.class]");
        session.getUserProperties().setProperty("failsafe.excludes", "**/*SlowIT.java");
        var decision = detector.evaluate(session, project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals(ProjectCompatibilityDetector.ExecutorKind.FAILSAFE, decision.executorKind());
        assertTrue(decision.includeClassNameRegexes().stream().anyMatch(regex -> Pattern.matches(regex, "example/SmokeIT.class")));
        assertTrue(decision.includeClassNameRegexes().stream().noneMatch(regex -> Pattern.matches(regex, "example/OtherIT.class")));
        assertFalse(decision.executorSystemProperties().containsKey(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS));
        assertFalse(decision.executorSystemProperties().containsKey(RuntimePropertyNames.MAVEN_EXCLUDED_TEST_PATTERNS));
    }

    @Test
    void itTestExpressionClearsFailsafeIncludeExcludeCollections() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"));
        project.getBuild().addPlugin(failsafeExecution("integration-tests", "%regex[.*OtherIT.class]"));
        MavenSession session = session("verify");
        session.getUserProperties().setProperty("failsafe.excludes", "!NeverMatchedIT");
        session.getUserProperties().setProperty("it.test", "CheckoutIT#pay*");
        var decision = detector.evaluate(session, project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals("CheckoutIT#pay*", decision.executorSystemProperties().get(RuntimePropertyNames.MAVEN_TEST_LIST_EXPRESSION));
        assertFalse(decision.executorSystemProperties().containsKey(RuntimePropertyNames.MAVEN_INCLUDED_TEST_PATTERNS));
        assertFalse(decision.executorSystemProperties().containsKey(RuntimePropertyNames.MAVEN_EXCLUDED_TEST_PATTERNS));
    }

    @Test
    void directJUnit4IsOnlyACandidateUntilRuntimePreflightProvesVintageOwnership() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"), dependency("junit", "junit"));
        var decision = detector.evaluate(session("test"), project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals(Set.of("junit-platform", "junit4-vintage"), decision.frameworks());
        assertTrue(decision.reason().contains("runtime preflight"), decision.reason());
    }

    @Test
    void multipleAdapterFrameworkSignalsStayNativeUntilCrossAdapterOwnershipIsProven() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"), dependency("org.testng", "testng"));
        var decision = detector.evaluate(session("test"), project);
        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("requires multiple ScenarioMesh adapters"), decision.reason());
    }

    @Test
    void unknownFrameworkDependencyCanReachRuntimePreflightWithoutBeingAssumedOwnable() {
        MavenProject project = project(dependency("com.example", "future-test-engine"));
        var decision = detector.evaluate(session("test"), project);
        assertTrue(decision.compatible(), decision.reason());
        assertTrue(decision.frameworks().isEmpty());
        assertTrue(decision.reason().contains("runtime preflight"), decision.reason());
    }

    @Test
    void cucumberJUnit4PlusTestNgStaysNativeUntilCrossAdapterOwnershipIsProven() {
        MavenProject project = project(dependency("io.cucumber", "cucumber-junit"), dependency("org.testng", "testng"), dependency("junit", "junit"));
        var decision = detector.evaluate(session("test"), project);
        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("requires multiple ScenarioMesh adapters"), decision.reason());
    }

    @Test
    void cucumberJUnit4DependencyCanStillTakeOverDespiteItsJUnit4Dependency() {
        MavenProject project = project(dependency("io.cucumber", "cucumber-junit"), dependency("junit", "junit"));
        var decision = detector.evaluate(session("test"), project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals(List.of("cucumber-junit4"), decision.frameworks().stream().sorted().toList());
    }

    @Test
    void effectiveGroupFilteringIsForwardedToFrameworkAdapters() {
        MavenProject project = project(dependency("org.testng", "testng"));
        project.getProperties().setProperty("groups", "smoke,api");
        project.getProperties().setProperty("excludedGroups", "slow");
        var decision = detector.evaluate(session("test"), project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals("smoke,api", decision.executorSystemProperties().get("groups"));
        assertEquals("slow", decision.executorSystemProperties().get("excludedGroups"));
    }

    @Test
    void mixedProviderGroupFilteringStaysNativeUntilItsOwnEquivalenceGate() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"), dependency("org.testng", "testng"));
        project.getProperties().setProperty("groups", "smoke");
        var decision = detector.evaluate(session("test"), project);
        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("requires multiple ScenarioMesh adapters"), decision.reason());
    }

    @Test
    void mixedJUnitPlatformAndTestNgStayNativeUntilCrossAdapterOwnershipIsProven() {
        MavenProject project = project(dependency("org.junit.jupiter", "junit-jupiter"), dependency("org.testng", "testng"));
        var decision = detector.evaluate(session("test"), project);
        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("requires multiple ScenarioMesh adapters"), decision.reason());
    }

    @Test
    void cucumberCliTagFilterIsForwardedIntoDiscoveryAndWorkers() {
        MavenProject project = project(dependency("io.cucumber", "cucumber-junit"), dependency("junit", "junit"));
        MavenSession session = session("test");
        session.getUserProperties().setProperty("cucumber.filter.tags", "@smoke and not @slow");
        var decision = detector.evaluate(session, project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals("@smoke and not @slow", decision.executorSystemProperties().get("cucumber.filter.tags"));
    }

    @Test
    void cucumberMavenJvmPropertyIsForwardedIncludingCustomPluginConfiguration() {
        MavenProject project = project(dependency("io.cucumber", "cucumber-junit"), dependency("junit", "junit"));
        MavenSession session = session("test");
        session.getSystemProperties().setProperty("cucumber.plugin", "com.example.CustomReporter,pretty");
        session.getSystemProperties().setProperty("cucumber.filter.name", "Checkout.*");
        var decision = detector.evaluate(session, project);
        assertTrue(decision.compatible(), decision.reason());
        assertEquals("com.example.CustomReporter,pretty", decision.executorSystemProperties().get("cucumber.plugin"));
        assertEquals("Checkout.*", decision.executorSystemProperties().get("cucumber.filter.name"));
    }

    @Test
    void projectOnlyCucumberSelectionFailsClosedBecauseNativeForkExposureIsAmbiguous() {
        MavenProject project = project(dependency("io.cucumber", "cucumber-junit"), dependency("junit", "junit"));
        project.getProperties().setProperty("cucumber.filter.tags", "@smoke");
        var decision = detector.evaluate(session("test"), project);
        assertFalse(decision.compatible());
        assertTrue(decision.reason().contains("defined only as a Maven project property"), decision.reason());
    }

    private boolean matchesAny(List<String> regexes, String className) {
        return regexes.stream().anyMatch(regex -> Pattern.matches(regex, className));
    }

    private MavenSession session(String... goals) {
        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setGoals(List.of(goals));
        return new MavenSession(null, null, request, new DefaultMavenExecutionResult());
    }

    private MavenProject project(Dependency... dependencies) {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("example");
        model.setArtifactId("fixture");
        model.setVersion("1.0");
        model.setBuild(new Build());
        for (Dependency dependency : dependencies) model.addDependency(dependency);
        return new MavenProject(model);
    }

    private Dependency dependency(String groupId, String artifactId) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion("1.0");
        dependency.setScope("test");
        return dependency;
    }

    private Plugin failsafeExecution(String id, String include) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-failsafe-plugin");
        PluginExecution execution = new PluginExecution();
        execution.setId(id);
        execution.setPhase("integration-test");
        execution.setGoals(List.of("integration-test", "verify"));
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom includes = new Xpp3Dom("includes");
        Xpp3Dom value = new Xpp3Dom("include");
        value.setValue(include);
        includes.addChild(value);
        configuration.addChild(includes);
        execution.setConfiguration(configuration);
        plugin.addExecution(execution);
        return plugin;
    }
}
