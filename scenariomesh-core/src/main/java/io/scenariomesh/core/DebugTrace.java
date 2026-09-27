package io.scenariomesh.core;

import java.time.Instant;
import java.util.Locale;

/**
 * Opt-in, low-overhead diagnostic trace for reconstructing ScenarioMesh decisions.
 *
 * <p>Enable with {@code -Dscenariomesh.debug=true} or {@code SCENARIOMESH_DEBUG=true}.
 * Disabled by default. Trace messages intentionally use stable short codes so CI logs can be
 * searched and supplied to humans or AI tools without requiring verbose logging.</p>
 */
public final class DebugTrace {
    public static final String PROPERTY = "scenariomesh.debug";
    public static final String ENVIRONMENT = "SCENARIOMESH_DEBUG";

    private DebugTrace() {}

    public static boolean enabled() {
        String property = System.getProperty(PROPERTY);
        if (property != null) return parse(property);
        return parse(System.getenv(ENVIRONMENT));
    }

    public static void log(String code, String message) {
        if (!enabled()) return;
        String safeCode = code == null || code.isBlank() ? "SMDBG-UNKNOWN" : code.trim();
        String safeMessage = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
        System.err.println("[" + safeCode + "] " + Instant.now() + " " + safeMessage);
    }

    public static void log(String code, String key, Object value) {
        log(code, key + "=" + String.valueOf(value));
    }

    private static boolean parse(String raw) {
        if (raw == null) return false;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            default -> false;
        };
    }
}
