package io.scenariomesh.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Explicit ScenarioMesh execution entrypoint that delegates to the production Maven plugin. */
final class RunCommand {
    int run(String[] args) {
        try {
            Path root = Path.of(".");
            List<String> forwarded = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                if ("--root".equals(args[i])) {
                    if (++i >= args.length) throw new IllegalArgumentException("--root requires a path");
                    root = Path.of(args[i]);
                } else {
                    forwarded.add(args[i]);
                }
            }
            List<String> command = new ArrayList<>();
            command.add(mavenExecutable());
            command.add("-B");
            command.add("test-compile");
            command.add("io.scenariomesh:scenariomesh-maven-plugin:" + ScenarioMeshVersion.current() + ":run");
            command.addAll(forwarded);
            Process process = new ProcessBuilder(command)
                    .directory(root.toAbsolutePath().normalize().toFile())
                    .inheritIO()
                    .start();
            if (!process.waitFor(24, TimeUnit.HOURS)) {
                process.destroyForcibly();
                System.err.println("ScenarioMesh run exceeded the CLI safety timeout.");
                return 124;
            }
            return process.exitValue();
        } catch (Exception exception) {
            System.err.println("ScenarioMesh run failed: " + exception.getMessage());
            return 2;
        }
    }

    private String mavenExecutable() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "mvn.cmd" : "mvn";
    }
}
