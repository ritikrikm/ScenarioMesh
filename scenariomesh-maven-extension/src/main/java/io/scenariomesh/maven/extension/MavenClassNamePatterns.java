package io.scenariomesh.maven.extension;

import java.util.List;

/** Shared translation for Surefire/Failsafe Ant-style class patterns. */
final class MavenClassNamePatterns {
    private MavenClassNamePatterns() {}

    static List<String> toRegexes(List<String> patterns) {
        return patterns.stream().map(MavenClassNamePatterns::toRegex).toList();
    }

    static String toRegex(String pattern) {
        String normalized = pattern.replace('\\', '/');
        if (normalized.endsWith(".java")) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - 6);
        }

        boolean optionalPackage = normalized.startsWith("**/");
        if (optionalPackage) {
            normalized = normalized.substring(3);
        }

        StringBuilder regex = new StringBuilder();
        if (optionalPackage) {
            regex.append("(?:.*\\.)?");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            if (ch == '/') {
                regex.append("\\.");
            } else if (ch == '*') {
                if (index + 1 < normalized.length() && normalized.charAt(index + 1) == '*') {
                    regex.append(".*");
                    index++;
                } else {
                    regex.append("[^.]*");
                }
            } else if (ch == '?') {
                regex.append("[^.]");
            } else if (".[]{}()+-^$|".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        return regex.toString();
    }
}
