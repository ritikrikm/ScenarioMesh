package io.scenariomesh.coordinator;

import io.scenariomesh.workerruntime.TargetClasspathDescriptor;
import io.scenariomesh.workerruntime.WorkerMain;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaProcessSupportTest {
    @Test
    void explicitJavaExecutableWinsOverCoordinatorJavaHome() {
        Path selected = Path.of("toolchains", "jdk-21", "bin", "java").toAbsolutePath().normalize();

        List<String> command = JavaProcessSupport.command(
                selected,
                List.of(Path.of("tests.jar")),
                List.of("-Xmx256m"),
                Map.of("env", "qa"),
                "example.Main",
                List.of("--run"));

        assertEquals(selected.toString(), command.get(0));
    }

    @Test
    void workerReceivesOnlySelfClearingTargetClasspathInternalProperty() {
        List<String> command = JavaProcessSupport.command(
                Path.of("java"),
                List.of(Path.of("control.jar")),
                List.of(),
                Map.of(
                        TargetClasspathDescriptor.SYSTEM_PROPERTY, "encoded-target-classpath",
                        "scenariomesh.internal.secretControlValue", "must-not-leak",
                        "target.property", "visible"),
                WorkerMain.class.getName(),
                List.of());

        assertTrue(command.contains("-D" + TargetClasspathDescriptor.SYSTEM_PROPERTY + "=encoded-target-classpath"));
        assertTrue(command.contains("-Dtarget.property=visible"));
        assertFalse(command.stream().anyMatch(value -> value.contains("secretControlValue") || value.contains("must-not-leak")));
    }
}
