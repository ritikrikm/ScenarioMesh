package io.scenariomesh.adapter.junitplatform;

import io.scenariomesh.core.DiscoverySelection;
import io.scenariomesh.maven.selection.SurefireTestSelection;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

import java.util.Objects;
import java.util.Optional;

/** Applies normalized Maven class selection and Surefire's public class+method predicate. */
public final class MavenClassSelectionPostFilter implements PostDiscoveryFilter {
    private final DiscoverySelection selection;
    private final SurefireTestSelection testSelection;

    public MavenClassSelectionPostFilter(DiscoverySelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
        if (selection.hasTestListExpression()) {
            this.testSelection = new SurefireTestSelection(selection.testListExpression());
        } else if (selection.hasConfiguredTestPatterns()) {
            this.testSelection = new SurefireTestSelection(
                    selection.includedTestPatterns(), selection.excludedTestPatterns());
        } else {
            this.testSelection = null;
        }
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        Optional<TestSource> source = descriptor.getSource();
        if (source.isEmpty()) return FilterResult.included("descriptor has no class source");
        TestSource value = source.get();
        if (value instanceof MethodSource methodSource) return methodResult(methodSource.getClassName(), methodSource.getMethodName());
        if (value instanceof ClassSource classSource) return classResult(classSource.getClassName());
        return FilterResult.included("non-class test source");
    }

    private FilterResult classResult(String className) {
        boolean selected = selection.matchesClassName(className)
                && (testSelection == null || testSelection.mayContainSelectedMethod(className));
        return selected
                ? FilterResult.included("class selected by effective Maven test selection")
                : FilterResult.excluded("class excluded by effective Maven test selection");
    }

    private FilterResult methodResult(String className, String methodName) {
        boolean selected = selection.matchesClassName(className)
                && (testSelection == null || testSelection.matches(className, methodName));
        return selected
                ? FilterResult.included("method selected by effective Maven test selection")
                : FilterResult.excluded("method excluded by effective Maven test selection");
    }
}
