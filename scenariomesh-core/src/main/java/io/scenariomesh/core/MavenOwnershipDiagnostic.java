package io.scenariomesh.core;

import java.util.Objects;

/** Stable, value-free ownership diagnostic shared by Maven model and runtime preflight. */
public final class MavenOwnershipDiagnostic {
    private MavenOwnershipDiagnostic() {}

    public enum Owner {
        SCENARIOMESH,
        FRAMEWORK_CAPSULE,
        PASS_THROUGH
    }

    public static String format(Owner owner,
                                String module,
                                String executor,
                                String execution,
                                String reason) {
        Objects.requireNonNull(owner, "owner");
        return "MAVEN_OWNERSHIP owner=" + owner.name()
                + " module=" + safeToken(module)
                + " executor=" + safeToken(executor)
                + " execution=" + safeToken(execution)
                + " reason=\"" + safeReason(reason) + "\"";
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.trim().replaceAll("[^A-Za-z0-9._:@/-]", "_");
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) return "unspecified";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
