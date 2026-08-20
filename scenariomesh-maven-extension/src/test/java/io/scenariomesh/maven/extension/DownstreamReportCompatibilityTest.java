package io.scenariomesh.maven.extension;

import io.scenariomesh.core.RuntimePropertyNames;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownstreamReportCompatibilityTest {
    private final DownstreamReportCompatibility compatibility = new DownstreamReportCompatibility();

    @Test
    void resolvesCluecumberSourceDirectoryAndPublishesRuntimeContract() {
        MavenProject project = project();
        Plugin plugin = cluecumber("${project.build.directory}/cucumber-report");
        project.getBuild().addPlugin(plugin);

        var analysis = compatibility.analyze(project);

        assertTrue(analysis.present());
        assertTrue(analysis.supported(), analysis.reason());
        assertEquals(
                new File(project.getBuild().getDirectory(), "cucumber-report").toPath().toAbsolutePath().normalize(),
                analysis.sourceJsonDirectory());
        assertEquals(
                analysis.sourceJsonDirectory().toString(),
                analysis.runtimeProperties().get(RuntimePropertyNames.CLUECUMBER_JSON_DIRECTORY));
    }

    @Test
    void unresolvedCluecumberDirectoryRefusesUnsafeTakeoverContract() {
        MavenProject project = project();
        project.getBuild().addPlugin(cluecumber("${unknown.directory}/cucumber-report"));

        var analysis = compatibility.analyze(project);

        assertTrue(analysis.present());
        assertFalse(analysis.supported());
        assertTrue(analysis.runtimeProperties().isEmpty());
    }

    private MavenProject project() {
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("example");
        model.setArtifactId("fixture");
        model.setVersion("1.0");
        Build build = new Build();
        build.setDirectory(new File("target").getAbsolutePath());
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        project.setFile(new File("pom.xml").getAbsoluteFile());
        return project;
    }

    private Plugin cluecumber(String sourceDirectory) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("com.trivago.rta");
        plugin.setArtifactId("cluecumber-report-plugin");
        plugin.setVersion("2.3.1");
        PluginExecution execution = new PluginExecution();
        execution.setId("report");
        execution.setPhase("post-integration-test");
        execution.addGoal("reporting");
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom source = new Xpp3Dom("sourceJsonReportDirectory");
        source.setValue(sourceDirectory);
        configuration.addChild(source);
        execution.setConfiguration(configuration);
        plugin.addExecution(execution);
        return plugin;
    }
}
