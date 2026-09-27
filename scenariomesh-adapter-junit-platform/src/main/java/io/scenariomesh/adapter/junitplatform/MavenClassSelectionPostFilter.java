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

/**
 * Applies normalized Maven class selection and Surefire's public class+method predicate.
 *
 * <p>Maven selects top-level test classes before handing them to the JUnit Platform. Once JUnit
 * discovers descendants such as {@code @Nested} classes, those descendants belong to the
 * already-selected outer class even though their binary names contain {@code $} and would be
 * excluded by Surefire's class-file scanner. Re-applying the scanner predicate directly to a
 * nested descriptor would incorrectly drop valid JUnit descendants.</p>
 */
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
        if (value instanceof MethodSource methodSource) {
            return methodResult(descriptor, methodSource.getClassName(), methodSource.getMethodName());
        }
        if (value instanceof ClassSource classSource) {
            return classResult(descriptor, classSource.getClassName());
        }
        return FilterResult.included("non-class test source");
    }

    private FilterResult classResult(TestDescriptor descriptor, String reportedClassName) {
        Optional<String> selectedClass = selectedMavenClass(descriptor, reportedClassName);
        boolean selected = selectedClass.isPresent()
                && (testSelection == null || testSelection.mayContainSelectedMethod(selectedClass.get()));
        return selected
                ? FilterResult.included("class is selected by or descends from the effective Maven test selection")
                : FilterResult.excluded("class excluded by effective Maven test selection");
    }

    private FilterResult methodResult(TestDescriptor descriptor, String reportedClassName, String methodName) {
        Optional<String> selectedClass = selectedMavenClass(descriptor, reportedClassName);
        boolean selected = selectedClass.isPresent()
                && (testSelection == null || testSelection.matches(selectedClass.get(), methodName));
        return selected
                ? FilterResult.included("method belongs to the effective Maven-selected JUnit hierarchy")
                : FilterResult.excluded("method excluded by effective Maven test selection");
    }

    /**
     * Returns the Maven-selected class that owns this JUnit descriptor.
     *
     * <p>The descriptor's own class may be a JUnit nested class that Surefire intentionally did
     * not scan as a top-level class file. In that case walk the JUnit descriptor ancestry until
     * the Maven-selected enclosing class is found. This follows framework hierarchy instead of
     * guessing from a {@code '$'} in the binary class name.</p>
     */
    private Optional<String> selectedMavenClass(TestDescriptor descriptor, String reportedClassName) {
        if (selection.matchesClassName(reportedClassName)) return Optional.of(reportedClassName);

        Optional<TestDescriptor> current = descriptor.getParent();
        while (current.isPresent()) {
            TestDescriptor ancestor = current.get();
            Optional<TestSource> source = ancestor.getSource();
            if (source.isPresent() && source.get() instanceof ClassSource classSource
                    && selection.matchesClassName(classSource.getClassName())) {
                return Optional.of(classSource.getClassName());
            }
            current = ancestor.getParent();
        }
        return Optional.empty();
    }
}
