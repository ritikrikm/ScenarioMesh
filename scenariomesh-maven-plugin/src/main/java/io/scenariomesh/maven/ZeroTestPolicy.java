package io.scenariomesh.maven;

/** Mirrors Surefire's distinction between an empty test set and an explicit selector matching nothing. */
final class ZeroTestPolicy {
    private ZeroTestPolicy() {}

    static String failureMessage(long discoveredTests,
                                 boolean enabled,
                                 boolean explicitTestSelection,
                                 boolean failIfNoTests,
                                 boolean failIfNoSpecifiedTests) {
        if (!enabled || discoveredTests > 0) return null;
        if (explicitTestSelection && failIfNoSpecifiedTests) {
            return "No tests matching the requested Surefire -Dtest selection were executed.";
        }
        if (failIfNoTests) {
            return "No tests were executed.";
        }
        return null;
    }
}
