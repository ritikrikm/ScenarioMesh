package io.scenariomesh.controljson;

import io.scenariomesh.core.ExecutionSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExecutionSpecJsonTest {
    @Test
    void roundTripsGenericExecutionContract() throws Exception {
        ExecutionSpec original = new ExecutionSpec(
                "execution-1", null,
                ExecutionSpec.ExecutionKind.MAVEN_PLUGIN_EXECUTION,
                ExecutionSpec.SemanticOwner.BUILD_TOOL,
                "Surefire default-test", "maven-surefire-plugin:default-test", "module-a",
                new ExecutionSpec.Requirements(Set.of(), Set.of(), Set.of("linux", "browser"),
                        2, 1_073_741_824L, 2),
                new ExecutionSpec.Policy(Duration.ofMinutes(20), Duration.ofSeconds(15),
                        ExecutionSpec.RetryClass.INFRASTRUCTURE_ONLY, 2),
                Map.of("module", "module-a"));

        String json = ControlJsonCodec.write(original);
        ExecutionSpec decoded = ControlJsonCodec.read(json, ExecutionSpec.class);

        assertEquals(original, decoded);
    }

    @Test
    void toleratesUnknownOptionalFieldsForForwardCompatibility() throws Exception {
        ExecutionSpec original = new ExecutionSpec(
                "execution-1", null, ExecutionSpec.ExecutionKind.TEST_CASE,
                ExecutionSpec.SemanticOwner.SCENARIOMESH, "test", "adapter", "selector",
                ExecutionSpec.Requirements.none(), ExecutionSpec.Policy.defaults(), Map.of());
        String futureJson = ControlJsonCodec.write(original).replaceFirst("\\{", "{\"futureField\":true,");

        ExecutionSpec decoded = ControlJsonCodec.read(futureJson, ExecutionSpec.class);

        assertEquals(original, decoded);
        assertFalse(futureJson.isBlank());
    }
}
