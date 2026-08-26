package io.scenariomesh.maven.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Conservative parser for the Maven Surefire/Failsafe class-selection syntax
 * that ScenarioMesh can reproduce exactly.
 *
 * <p>The goal is deliberately not to emulate every Surefire feature. Patterns
 * are first classified. Supported patterns are translated with class-file path
 * semantics; unsupported constructs are rejected so the caller can pass the
 * build through to native Maven rather than silently select a different set.</p>
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
        if (!analysis.unsupportedReasons().isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", analysis.unsupportedReasons()));
        }
        return analysis.patterns().stream().map(CompiledPattern::regex).toList();
    }

    static String toRegex(String pattern) {
        SelectionAnalysis analysis = analyze(List.of(pattern));
        if (!analysis.unsupportedReasons().isEmpty() || analysis.patterns().size() != 1) {
            throw new IllegalArgumentException(analysis.unsupportedReasons().isEmpty()
                    ? "Pattern did not resolve to exactly one Maven class selector: " + pattern
                    : String.join("; ", analysis.unsupportedReasons()));
        }
        return analysis.patterns().get(0).regex();
    }

    private static CompiledPattern compile(String raw) {
        if (raw.startsWith("!")) {
            // Surefire supports inline negation in several selection surfaces. ScenarioMesh
            // currently carries include/exclude lists separately, so silently converting an
            // inline negation would lose ordering/combination semantics.
            throw unsupported(raw, "inline negation is not yet represented exactly; use native Maven pass-through");
        }
        String body = raw.trim();

        if (body.startsWith("%regex[") && body.endsWith("]")) {
            String expression = body.substring("%regex[".length(), body.length() - 1);
            try {
                Pattern.compile(expression);
            } catch (PatternSyntaxException exception) {
                throw unsupported(raw, "invalid %regex expression: " + exception.getDescription());
            }
            // Surefire/Failsafe regex selectors are evaluated against .class paths.
            return new CompiledPattern(raw, PatternKind.REGEX_CLASS_PATH, expression);
        }

        if (body.contains("#")) throw unsupported(raw, "method selectors are not class-selection patterns");
        if (body.contains("%regex[") || body.contains("%regex")) throw unsupported(raw, "malformed %regex selector");
        if (body.indexOf('[') >= 0 || body.indexOf(']') >= 0 || body.indexOf('{') >= 0 || body.indexOf('}') >= 0) {
            throw unsupported(raw, "character classes/braces are outside ScenarioMesh's proven Maven glob subset");
        }

        String normalized = body.replace('\\', '/');
        if (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.endsWith(".java")) normalized = normalized.substring(0, normalized.length() - 5) + ".class";
        else if (!normalized.endsWith(".class")) normalized = normalized + ".class";

        return new CompiledPattern(raw, PatternKind.GLOB_CLASS_PATH, globToRegex(normalized));
    }

    private static String globToRegex(String normalized) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            if (ch == '*') {
                if (index + 1 < normalized.length() && normalized.charAt(index + 1) == '*') {
                    index++;
                    if (index + 1 < normalized.length() && normalized.charAt(index + 1) == '/') {
                        regex.append("(?:.*/)?");
                        index++;
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (ch == '?') {
                regex.append("[^/]");
            } else if (".[]{}()+-^$|\\".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        regex.append('$');
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
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static UnsupportedPatternException unsupported(String pattern, String reason) {
        return new UnsupportedPatternException("unsupported Maven class selector '" + pattern + "': " + reason);
    }

    enum PatternKind { GLOB_CLASS_PATH, REGEX_CLASS_PATH }
    record CompiledPattern(String source, PatternKind kind, String regex) {}

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
