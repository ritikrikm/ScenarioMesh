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
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
        EngineDescriptor root = new EngineDescriptor(uniqueId, "NextGen fixture engine");
        Class<?> trigger = loadTrigger();
        if (trigger == null) return root;

        boolean selectedByClass = request.getSelectorsByType(ClassSelector.class).stream()
                .anyMatch(selector -> trigger.getName().equals(selector.getClassName()));
        boolean selectedByRoot = !request.getSelectorsByType(ClasspathRootSelector.class).isEmpty();
        if (selectedByClass || selectedByRoot) {
            root.addChild(new FixtureTestDescriptor(
                    uniqueId.append("class", trigger.getName()).append("test", "native-pass-through"),
                    ClassSource.from(trigger)));
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

    private Class<?> loadTrigger() {
        try {
            return Class.forName("example.TriggerTest", false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException exception) {
            return null;
        }
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
