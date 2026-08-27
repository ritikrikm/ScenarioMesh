package io.scenariomesh.coordinator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
