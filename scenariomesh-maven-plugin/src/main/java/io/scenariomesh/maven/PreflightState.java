package io.scenariomesh.maven;

import org.apache.maven.project.MavenProject;

/** Project-scoped handoff between ScenarioMesh preflight and the run goal. */
final class PreflightState {
    private static final String KEY = "scenariomesh.preflight.state";
    private static final String REASON_KEY = "scenariomesh.preflight.reason";

    private PreflightState() {}

    static void owned(MavenProject project, String reason) {
        project.getProperties().setProperty(KEY, "owned");
        setReason(project, reason);
    }

    static void passThrough(MavenProject project, String reason) {
        project.getProperties().setProperty(KEY, "pass-through");
        setReason(project, reason);
    }

    static State read(MavenProject project) {
        String value = project.getProperties().getProperty(KEY);
        if ("owned".equals(value)) return State.OWNED;
        if ("pass-through".equals(value)) return State.PASS_THROUGH;
        return State.NOT_RUN;
    }

    static String reason(MavenProject project) {
        return project.getProperties().getProperty(REASON_KEY, "");
    }

    private static void setReason(MavenProject project, String reason) {
        if (reason == null || reason.isBlank()) project.getProperties().remove(REASON_KEY);
        else project.getProperties().setProperty(REASON_KEY, reason);
    }

    enum State {
        OWNED,
        PASS_THROUGH,
        NOT_RUN
    }
}
