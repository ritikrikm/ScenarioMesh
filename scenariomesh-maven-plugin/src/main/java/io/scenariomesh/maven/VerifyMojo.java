package io.scenariomesh.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.nio.file.Path;

/**
 * Mirrors Failsafe's deferred-failure behavior: integration-test execution may
 * complete and allow post-integration-test cleanup, while the build result is
 * finalized during verify.
 */
@Mojo(name = "verify", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class VerifyMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(required = true)
    private String invocationId;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (PreflightState.read(project) == PreflightState.State.PASS_THROUGH) {
            getLog().info("ScenarioMesh verify: runtime preflight selected native Maven pass-through; deferred verification is inactive. "
                    + PreflightState.reason(project));
            return;
        }

        Path buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        try {
            DeferredVerificationState.State state = DeferredVerificationState.read(buildDirectory);
            if (!invocationId.equals(state.invocationId())) {
                throw new MojoExecutionException(
                        "ScenarioMesh verification state belongs to a different Maven invocation. "
                                + "Expected " + invocationId + " but found " + state.invocationId());
            }
            if (!state.successful()) {
                String detail = state.message() == null ? "ScenarioMesh integration-test execution failed" : state.message();
                String report = state.report() == null ? "" : " See " + state.report();
                throw new MojoFailureException(detail + "." + report);
            }
            getLog().info("ScenarioMesh verify: deferred integration-test result is successful.");
        } catch (MojoFailureException | MojoExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MojoExecutionException("ScenarioMesh could not verify deferred integration-test state: "
                    + exception.getMessage(), exception);
        }
    }
}
