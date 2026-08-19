package io.scenariomesh.maven;

import io.scenariomesh.config.ConfigResolver;
import io.scenariomesh.config.ConfigResolver.ConfigResolution;
import io.scenariomesh.config.ScenarioMeshConfig;
import io.scenariomesh.coordinator.RunOutcome;
import io.scenariomesh.coordinator.RunRequest;
import io.scenariomesh.coordinator.ScenarioMeshRunner;
import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.reporting.ReportWriter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mojo(name = "run", defaultPhase = LifecyclePhase.TEST, threadSafe = true,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST)
public final class RunMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;

    @Parameter
    private String invocationId;
    @Parameter(defaultValue = "false")
    private boolean deferFailureUntilVerify;
    @Parameter(defaultValue = "surefire")
    private String takeoverExecutor;
    @Parameter
    private List<String> includeClassNameRegexes;
    @Parameter
    private List<String> excludeClassNameRegexes;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if ("true".equalsIgnoreCase(session.getUserProperties().getProperty("skipTests"))
                || "true".equalsIgnoreCase(session.getUserProperties().getProperty("maven.test.skip"))) {
            getLog().info("ScenarioMesh: tests were explicitly skipped by the Maven command.");
            return;
        }

        Path buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        try {
            Map<String,String> userProperties=stringProperties(session.getUserProperties());
            Map<String,String> configProperties=stringProperties(session.getSystemProperties());
            configProperties.putAll(userProperties);
            Path projectDirectory=project.getBasedir().toPath().toAbsolutePath().normalize();
            ConfigResolution resolution=new ConfigResolver().resolveDetailed(projectDirectory,buildDirectory,configProperties,System.getenv());
            ScenarioMeshConfig config=resolution.config();
            if(!config.enabled()){
                getLog().info("ScenarioMesh disabled; normal Maven test execution remains active.");
                return;
            }

            DiscoverySelection selection=new DiscoverySelection(
                    includeClassNameRegexes==null?List.of():includeClassNameRegexes,
                    excludeClassNameRegexes==null?List.of():excludeClassNameRegexes);

            getLog().info("ScenarioMesh 0.1.0-SNAPSHOT");
            getLog().info("Project: "+project.getArtifactId());
            getLog().info("Maven executor takeover: "+takeoverExecutor);
            resolution.configFile().ifPresent(path->getLog().info("Config: "+path));
            getLog().info("Adapter intent: "+config.executionAdapter());
            getLog().info("Workers: "+config.workerCount());

            RunRequest request=new RunRequest(projectDirectory,runtimeClasspath(),testRoots(),userProperties,config,selection);
            RunOutcome outcome=new ScenarioMeshRunner().run(request);
            ReportWriter.ReportPaths reports=new ReportWriter().write(outcome,config.reportingDirectory());
            long passed=outcome.results().stream().filter(result->result.passed()).count();
            long failed=outcome.results().size()-passed;
            getLog().info("Selected adapter: "+String.join(", ",outcome.adapters()));
            getLog().info("Discovered: "+outcome.tasks().size());
            getLog().info("Passed: "+passed+", Failed: "+failed);
            getLog().info("Report: "+reports.latestHtml());

            if(deferFailureUntilVerify){
                DeferredVerificationState.write(buildDirectory,invocationId,outcome.successful(),reports.latestHtml().toString(),
                        outcome.successful()?null:"ScenarioMesh run contained failing or infrastructure results");
                if(!outcome.successful()){
                    getLog().warn("ScenarioMesh recorded failures for Maven verify; post-integration-test lifecycle phases will continue.");
                }
                return;
            }
            if(!outcome.successful())throw new MojoFailureException("ScenarioMesh run failed. See "+reports.latestHtml());
        } catch (MojoFailureException failure) {
            throw failure;
        } catch (Exception exception) {
            if(deferFailureUntilVerify){
                try{
                    DeferredVerificationState.write(buildDirectory,invocationId,false,null,
                            "ScenarioMesh infrastructure failure: "+exception.getMessage());
                    getLog().error("ScenarioMesh infrastructure failure recorded for Maven verify: "+exception.getMessage());
                    return;
                }catch(Exception stateFailure){
                    exception.addSuppressed(stateFailure);
                }
            }
            throw new MojoExecutionException("ScenarioMesh infrastructure failure: "+exception.getMessage(),exception);
        }
    }

    private Map<String,String> stringProperties(java.util.Properties properties){Map<String,String> values=new LinkedHashMap<>();if(properties!=null)properties.forEach((key,value)->values.put(String.valueOf(key),String.valueOf(value)));return values;}
    private List<Path> runtimeClasspath() throws Exception {Set<Path> paths=new LinkedHashSet<>();for(String element:project.getTestClasspathElements())paths.add(Path.of(element).toAbsolutePath().normalize());if(pluginArtifacts!=null)for(Artifact artifact:pluginArtifacts){File file=artifact.getFile();if(file!=null&&file.exists())paths.add(file.toPath().toAbsolutePath().normalize());}return List.copyOf(paths);}
    private List<Path> testRoots(){List<Path> roots=new ArrayList<>();Path standard=Path.of(project.getBuild().getTestOutputDirectory()).toAbsolutePath().normalize();if(Files.isDirectory(standard))roots.add(standard);return List.copyOf(roots);}
}
