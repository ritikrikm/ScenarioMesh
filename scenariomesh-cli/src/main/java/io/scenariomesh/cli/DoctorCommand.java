package io.scenariomesh.cli;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

            CommandResult maven = command(root, List.of(mavenExecutable(), "-version"));
            boolean mavenOk = maven.exitCode == 0;
            String mavenLine = Arrays.stream(maven.output.split("\\R"))
                    .filter(line -> line.startsWith("Apache Maven ")).findFirst().orElse(maven.output.strip());
            check("Maven executable", mavenOk, mavenLine.isBlank() ? "not available" : mavenLine);

            if (!javaOk || !pomOk || !mavenOk) return 2;
            if (!extensionOk) return 3;

            if (parsed.deep) {
                String goal = "io.scenariomesh:scenariomesh-maven-plugin:"
                        + ScenarioMeshVersion.current() + ":preflight";
                CommandResult deep = command(root, List.of(mavenExecutable(), "-B", "test-compile", goal));
                System.out.print(deep.output);
                if (deep.exitCode != 0) {
                    check("Deep compatibility probe", false, "Maven/preflight failed with exit code " + deep.exitCode);
                    return 4;
                }
                if (deep.output.contains("ScenarioMesh preflight: ownership proven")) {
                    check("Acceleration", true, "ScenarioMesh can safely own this test execution");
                } else if (deep.output.contains("ScenarioMesh preflight: native Maven pass-through")) {
                    check("Acceleration", false, "safe native Maven pass-through; see reason above");
                } else {
                    check("Acceleration", false, "preflight produced no ownership decision");
                }
            }

            return 0;
        } catch (Exception exception) {
            System.err.println("ScenarioMesh doctor failed: " + exception.getMessage());
            return 2;
        }
    }

    private CommandResult command(Path root, List<String> command) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(command))
                .directory(root.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofPlatform().daemon().start(() -> {
            try { process.getInputStream().transferTo(output); } catch (Exception ignored) { }
        });
        if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            reader.join(1000);
            return new CommandResult(124, output.toString(StandardCharsets.UTF_8) + "\nCommand timed out\n");
        }
        reader.join(1000);
        return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private String mavenExecutable() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
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
