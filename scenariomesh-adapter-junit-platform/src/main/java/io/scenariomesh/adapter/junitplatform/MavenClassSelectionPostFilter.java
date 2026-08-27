package io.scenariomesh.adapter.junitplatform;

import io.scenariomesh.core.DiscoverySelection;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

import java.util.Objects;
import java.util.Optional;

/**
 * Applies Maven/Surefire/Failsafe class selection to class-backed JUnit Platform
 * descriptors without suppressing resource-backed engines such as Cucumber.
 *
 * <p>Classpath-root discovery is intentionally retained so every TestEngine sees
 * its native discovery surface. Descriptors that identify a Java class or method
 * are then filtered against the already-normalized Maven selection. Resource,
 * package, engine, and other non-class descriptors are left in the plan so their
 * children can be evaluated by their own source semantics.</p>
 */
public final class MavenClassSelectionPostFilter implements PostDiscoveryFilter {
    private final DiscoverySelection selection;

    public MavenClassSelectionPostFilter(DiscoverySelection selection) {
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        Optional<TestSource> source = descriptor.getSource();
        if (source.isEmpty()) return FilterResult.included("descriptor has no class source");
        TestSource value = source.get();
        if (value instanceof ClassSource classSource) {
            return classResult(classSource.getClassName());
        }
        if (value instanceof MethodSource methodSource) {
            return classResult(methodSource.getClassName());
        }
        return FilterResult.included("non-class test source");
    }

    private FilterResult classResult(String className) {
        return selection.matchesClassName(className)
                ? FilterResult.included("class selected by effective Maven test selection")
                : FilterResult.excluded("class excluded by effective Maven test selection");
    }
}
