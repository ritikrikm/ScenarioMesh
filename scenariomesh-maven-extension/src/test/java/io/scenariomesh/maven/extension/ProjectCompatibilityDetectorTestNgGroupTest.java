package io.scenariomesh.maven.extension;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCompatibilityDetectorTestNgGroupTest {
    private final ProjectCompatibilityDetector detector = new ProjectCompatibilityDetector();

    @Test
    void suiteXmlFilesKeepTestNgAsTheSelectionAuthority() {
        MavenProject project = testNgProjectWithSuiteXml();
        MavenSession session = session("test");
        session.getUserProperties().setProperty("groups", "smoke");

        var decision = detector.evaluate(session, project);

        assertTrue(decision.compatible(), decision.reason());
    }

    private MavenProject testNgProjectWithSuiteXml() {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("example");
        model.setArtifactId("fixture");
        model.setVersion("1.0");

        Dependency testNg = new Dependency();
        testNg.setGroupId("org.testng");
        testNg.setArtifactId("testng");
        testNg.setVersion("7.10.2");
        testNg.setScope("test");
        model.addDependency(testNg);

        Plugin surefire = new Plugin();
        surefire.setGroupId("org.apache.maven.plugins");
        surefire.setArtifactId("maven-surefire-plugin");
        surefire.setVersion("3.5.2");
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom suiteXmlFiles = new Xpp3Dom("suiteXmlFiles");
        Xpp3Dom suiteXmlFile = new Xpp3Dom("suiteXmlFile");
        suiteXmlFile.setValue("testng.xml");
        suiteXmlFiles.addChild(suiteXmlFile);
        configuration.addChild(suiteXmlFiles);
        surefire.setConfiguration(configuration);

        Build build = new Build();
        build.addPlugin(surefire);
        model.setBuild(build);
        return new MavenProject(model);
    }

    private MavenSession session(String... goals) {
        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setGoals(List.of(goals));
        return new MavenSession(null, null, request, new DefaultMavenExecutionResult());
    }
}
