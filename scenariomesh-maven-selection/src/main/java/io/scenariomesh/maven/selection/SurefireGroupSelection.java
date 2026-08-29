package io.scenariomesh.maven.selection;

import org.apache.maven.surefire.group.match.AndGroupMatcher;
import org.apache.maven.surefire.group.match.GroupMatcher;
import org.apache.maven.surefire.group.match.InverseGroupMatcher;
import org.apache.maven.surefire.group.parse.GroupMatcherParser;
import org.apache.maven.surefire.group.parse.ParseException;

/**
 * Public ScenarioMesh wrapper around Surefire's own group-expression grammar.
 * This keeps Maven/TestNG group selection aligned with the provider rather than
 * approximating the expression language inside framework adapters.
 */
public final class SurefireGroupSelection {
    private final GroupMatcher matcher;

    private SurefireGroupSelection(GroupMatcher matcher) {
        this.matcher = matcher;
    }

    public static SurefireGroupSelection fromExpressions(String groups, String excludedGroups) {
        try {
            AndGroupMatcher combined = new AndGroupMatcher();
            GroupMatcher included = parseIfPresent(groups);
            GroupMatcher excluded = parseIfPresent(excludedGroups);
            if (included != null) combined.addMatcher(included);
            if (excluded != null) combined.addMatcher(new InverseGroupMatcher(excluded));
            return new SurefireGroupSelection(included == null && excluded == null ? null : combined);
        } catch (ParseException exception) {
            throw new IllegalArgumentException(
                    "Cannot parse Surefire group includes/excludes expression(s): includes='"
                            + groups + "' excludes='" + excludedGroups + "'", exception);
        }
    }

    public boolean matches(String... groups) {
        return matcher == null || matcher.enabled(groups == null ? new String[0] : groups);
    }

    private static GroupMatcher parseIfPresent(String expression) throws ParseException {
        return expression == null || expression.trim().isEmpty()
                ? null
                : new GroupMatcherParser(expression).parse();
    }
}
