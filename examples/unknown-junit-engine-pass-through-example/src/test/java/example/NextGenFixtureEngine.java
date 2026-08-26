package example;

import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.ClasspathRootSelector;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

public final class NextGenFixtureEngine implements TestEngine {
    @Override
    public String getId() {
        return "nextgen-fixture";
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest discoveryRequest, UniqueId uniqueId) {
        EngineDescriptor root = new EngineDescriptor(uniqueId, "NextGen fixture engine");

        boolean selectedByClass = discoveryRequest.getSelectorsByType(ClassSelector.class).stream()
                .anyMatch(selector -> TriggerTest.class.getName().equals(selector.getClassName()));
        boolean selectedByClasspathRoot = !discoveryRequest.getSelectorsByType(ClasspathRootSelector.class).isEmpty();

        if (selectedByClass || selectedByClasspathRoot) {
            root.addChild(new FixtureTestDescriptor(
                    uniqueId.append("class", TriggerTest.class.getName()).append("test", "native-pass-through"),
                    ClassSource.from(TriggerTest.class)));
        }
        return root;
    }

    @Override
    public void execute(ExecutionRequest request) {
        EngineExecutionListener listener = request.getEngineExecutionListener();
        TestDescriptor root = request.getRootTestDescriptor();
        listener.executionStarted(root);
        for (TestDescriptor child : root.getChildren()) {
            listener.executionStarted(child);
            listener.executionFinished(child, TestExecutionResult.successful());
        }
        listener.executionFinished(root, TestExecutionResult.successful());
    }

    private static final class FixtureTestDescriptor extends AbstractTestDescriptor {
        private FixtureTestDescriptor(UniqueId uniqueId, ClassSource source) {
            super(uniqueId, "nativePassThrough", source);
        }

        @Override
        public Type getType() {
            return Type.TEST;
        }
    }
}
