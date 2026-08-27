package io.scenariomesh.cli;

import io.scenariomesh.config.ConfigResolver;
import io.scenariomesh.config.ScenarioMeshConfig;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class DoctorCommand {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(3);

    int run(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            Path root = parsed.root.toAbsolutePath().normalize();
            System.out.println("ScenarioMesh doctor");
            System.out.println("Repository: " + root);

            int javaFeature = Runtime.version().feature();
            boolean javaOk = javaFeature >= 17;
            check("ScenarioMesh runtime Java", javaOk,
                    System.getProperty("java.version") + (javaOk ? "" : " (Java 17+ required)"));

            Path pom = root.resolve("pom.xml");
            boolean pomOk = Files.isRegularFile(pom);
            check("Maven project", pomOk, pomOk ? pom.toString() : "pom.xml not found");

            Path extension = root.resolve(".mvn/extensions.xml");
            boolean extensionOk = Files.isRegularFile(extension)
                    && Files.readString(extension).contains("scenariomesh-maven-extension");
            check("ScenarioMesh Maven extension", extensionOk,
                    extensionOk ? extension.toString() : "not installed; run 'scenariomesh init'");

            ScenarioMeshConfig config = null;
            try {
                config = new ConfigResolver().resolve(root, root.resolve("target"), systemProperties(), System.getenv());
                check("Configuration schema", true, "valid");
                check("Worker mode", true, config.distributed().mode().externalValue());
                if (config.distributed().remote()) {
                    check("Distributed transport", true,
                            config.distributed().tls().enabled() ? "TLS enabled"
                                    : "loopback-only plaintext; non-loopback is rejected by configuration validation");
                    if (config.distributed().tls().enabled()) {
                        check("TLS client authentication", config.distributed().tls().requireClientAuth(),
                                config.distributed().tls().requireClientAuth() ? "mutual TLS required" : "server-auth TLS only");
                    }
                }
            } catch (Exception invalidConfig) {
                check("Configuration schema", false, invalidConfig.getMessage());
                return 5;
            }

            CommandResult maven = command(root, List.of(mavenExecutable(), "-version"));
            boolean mavenOk = maven.exitCode == 0;
            String mavenLine = Arrays.stream(maven.output.split("\\R"))
                    .filter(line -> line.startsWith("Apache Maven ")).findFirst().orElse(maven.output.strip());
            check("Maven executable", mavenOk, mavenLine.isBlank() ? "not available" : mavenLine);
            if (mavenOk && mavenLine.startsWith("Apache Maven 4.")) {
                check("Maven 4 support level", false,
                        "preview compatibility only until Apache Maven 4 reaches GA; native pass-through remains the safety fallback");
            } else if (mavenOk) {
                check("Maven support line", mavenLine.matches(".*Apache Maven 3\\.9\\..*"),
                        mavenLine.contains("Apache Maven 3.9.") ? "supported Maven 3.9.x line" : "outside the primary tested Maven 3.9.x line");
            }

            if (!javaOk || !pomOk || !mavenOk) return 2;
            if (!extensionOk) return 3;

            if (parsed.deep) {
                String goal = "io.scenariomesh:scenariomesh-maven-plugin:" + ScenarioMeshVersion.current() + ":preflight";
                CommandResult deep = command(root, List.of(mavenExecutable(), "-B", "test-compile", goal));
                System.out.print(deep.output);
                if (deep.exitCode != 0) {
                    check("Deep compatibility probe", false, "Maven/preflight failed with exit code " + deep.exitCode);
                    return 4;
                }
                if (deep.output.contains("ScenarioMesh preflight: ownership proven")) {
                    check("Ownership decision", true, "ScenarioMesh can safely own this test execution");
                } else if (deep.output.contains("ScenarioMesh preflight: native Maven pass-through")) {
                    check("Ownership decision", false, "safe native Maven pass-through; exact reason is printed above");
                } else {
                    check("Ownership decision", false, "preflight produced no ownership decision");
                }
            }
            return 0;
        } catch (Exception exception) {
            System.err.println("ScenarioMesh doctor failed: " + exception.getMessage());
            return 2;
        }
    }

    private CommandResult command(Path root, List<String> command) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(command)).directory(root.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try { process.getInputStream().transferTo(output); } catch (Exception ignored) { }
        }, "scenariomesh-doctor-output");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            reader.join(1000);
            return new CommandResult(124, output.toString(StandardCharsets.UTF_8) + "\nCommand timed out\n");
        }
        reader.join(1000);
        return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private Map<String, String> systemProperties() {
        Map<String, String> values = new LinkedHashMap<>();
        System.getProperties().forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        return values;
    }

    private String mavenExecutable() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "mvn.cmd" : "mvn";
    }

    private void check(String name, boolean ok, String detail) {
        System.out.printf("%-30s %s  %s%n", name, ok ? "OK" : "WARN", detail);
    }

    private record CommandResult(int exitCode, String output) {}
    private static final class Arguments {
        private Path root = Path.of(".");
        private boolean deep;
        static Arguments parse(String[] args) {
            Arguments result = new Arguments();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--deep" -> result.deep = true;
                    case "--root" -> {
                        if (++i >= args.length) throw new IllegalArgumentException("--root requires a path");
                        result.root = Path.of(args[i]);
                    }
                    default -> throw new IllegalArgumentException("Unknown doctor option: " + args[i]);
                }
            }
            return result;
        }
    }
}
