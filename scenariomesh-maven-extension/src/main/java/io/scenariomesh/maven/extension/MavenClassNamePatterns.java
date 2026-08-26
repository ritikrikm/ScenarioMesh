package io.scenariomesh.maven.extension;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative parser for the Maven Surefire/Failsafe class-selection syntax
 * that ScenarioMesh can reproduce exactly across every shipped adapter.
 * Unsupported constructs are rejected so callers can retain native Maven
 * semantics rather than silently selecting a different test set.
 */
final class MavenClassNamePatterns {
    private MavenClassNamePatterns() {}

    static SelectionAnalysis analyze(List<String> patterns) {
        List<CompiledPattern> compiled = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        if (patterns == null) return new SelectionAnalysis(List.of(), List.of());

        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) continue;
            for (String token : splitTopLevel(raw)) {
                String value = token.trim();
                if (value.isEmpty()) continue;
                try {
                    compiled.add(compile(value));
                } catch (UnsupportedPatternException exception) {
                    unsupported.add(exception.getMessage());
                }
            }
        }
        return new SelectionAnalysis(List.copyOf(compiled), List.copyOf(unsupported));
    }

    static List<String> toRegexes(List<String> patterns) {
        SelectionAnalysis analysis = analyze(patterns);
        if (!analysis.supported()) throw new IllegalArgumentException(String.join("; ", analysis.unsupportedReasons()));
        return analysis.patterns().stream().map(CompiledPattern::regex).toList();
    }

    static String toRegex(String pattern) {
        SelectionAnalysis analysis = analyze(List.of(pattern));
        if (!analysis.supported() || analysis.patterns().size() != 1) {
            throw new IllegalArgumentException(analysis.unsupportedReasons().isEmpty()
                    ? "Pattern did not resolve to exactly one Maven class selector: " + pattern
                    : String.join("; ", analysis.unsupportedReasons()));
        }
        return analysis.patterns().get(0).regex();
    }

    private static CompiledPattern compile(String raw) {
        if (raw.startsWith("!")) {
            throw unsupported(raw, "inline negation is not yet represented exactly");
        }
        String body = raw.trim();
        if (body.startsWith("%regex[") || body.contains("%regex")) {
            // Surefire evaluates %regex against class-file paths while JUnit Platform's
            // ClassNameFilter evaluates dotted class names. Until ScenarioMesh carries a
            // typed selection object all the way to each adapter, accepting this would make
            // semantics adapter-dependent.
            throw unsupported(raw, "%regex class-path semantics are not yet portable across all adapters");
        }
        if (body.contains("#")) throw unsupported(raw, "method selectors are not class-selection patterns");
        if (body.indexOf('[') >= 0 || body.indexOf(']') >= 0 || body.indexOf('{') >= 0 || body.indexOf('}') >= 0) {
            throw unsupported(raw, "character classes/braces are outside ScenarioMesh's proven Maven glob subset");
        }

        String normalized = body.replace('\\', '/');
        if (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.endsWith(".java")) normalized = normalized.substring(0, normalized.length() - 5);
        if (normalized.endsWith(".class")) normalized = normalized.substring(0, normalized.length() - 6);

        String pathRegex = globToRegex(normalized, '/');
        String dottedRegex = globToRegex(normalized.replace('/', '.'), '.');
        // DiscoverySelection understands both representations, while JUnit Platform's
        // ClassNameFilter can consume the dotted alternative directly.
        return new CompiledPattern(raw, "(?:" + pathRegex + "|" + dottedRegex + ")");
    }

    private static String globToRegex(String value, char separator) {
        String sep = separator == '/' ? "/" : "\\.";
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '*') {
                if (index + 1 < value.length() && value.charAt(index + 1) == '*') {
                    index++;
                    char following = index + 1 < value.length() ? value.charAt(index + 1) : 0;
                    if (following == separator) {
                        regex.append("(?:.*").append(sep).append(")?");
                        index++;
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append(separator == '/' ? "[^/]*" : "[^.]*");
                }
            } else if (ch == '?') {
                regex.append(separator == '/' ? "[^/]" : "[^.]");
            } else if (ch == separator) {
                regex.append(sep);
            } else if (".[]{}()+-^$|\\".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        return regex.toString();
    }

    private static List<String> splitTopLevel(String raw) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int regexDepth = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (i + 7 <= raw.length() && raw.regionMatches(true, i, "%regex[", 0, 7)) regexDepth++;
            char ch = raw.charAt(i);
            if (ch == ']' && regexDepth > 0) regexDepth--;
            if (ch == ',' && regexDepth == 0) {
                values.add(current.toString());
                current.setLength(0);
            } else current.append(ch);
        }
        values.add(current.toString());
        return values;
    }

    private static UnsupportedPatternException unsupported(String pattern, String reason) {
        return new UnsupportedPatternException("unsupported Maven class selector '" + pattern + "': " + reason);
    }

    record CompiledPattern(String source, String regex) {}
    record SelectionAnalysis(List<CompiledPattern> patterns, List<String> unsupportedReasons) {
        SelectionAnalysis {
            patterns = List.copyOf(patterns == null ? List.of() : patterns);
            unsupportedReasons = List.copyOf(unsupportedReasons == null ? List.of() : unsupportedReasons);
        }
        boolean supported() { return unsupportedReasons.isEmpty(); }
    }
    private static final class UnsupportedPatternException extends RuntimeException {
        private UnsupportedPatternException(String message) { super(message); }
    }
}
