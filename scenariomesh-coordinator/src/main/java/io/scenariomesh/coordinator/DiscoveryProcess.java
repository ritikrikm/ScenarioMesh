package io.scenariomesh.coordinator;

import io.scenariomesh.workerruntime.DiscoveryMain;
import io.scenariomesh.workerruntime.DiscoveryResultCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class DiscoveryProcess {
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);

    DiscoveryMain.DiscoveryResult discover(RunRequest request, Path directory) throws Exception {
        Path output = directory.resolve("discovered-scenarios.bin");
        Path log = directory.resolve("discovery.log");
        List<String> args = new ArrayList<>();
        args.add("--output");
        args.add(output.toString());
        args.add("--adapter");
        args.add(request.config().executionAdapter());
        args.add("--adapter-mismatch-policy");
        args.add(request.config().adapterMismatchPolicy().externalValue());
        for (Path root : request.testRoots()) {
            args.add("--test-root");
            args.add(root.toString());
        }
        for (String regex : request.discoverySelection().includeClassNameRegexes()) {
            args.add("--include-class-regex");
            args.add(regex);
        }
        for (String regex : request.discoverySelection().excludeClassNameRegexes()) {
            args.add("--exclude-class-regex");
            args.add(regex);
        }

        List<String> command = JavaProcessSupport.command(
                request.javaExecutable(),
                request.runtimeClasspath(),
                request.effectiveJvmArgs(),
                request.effectiveSystemProperties(),
                DiscoveryMain.class.getName(),
                args);

        Process process = new ProcessBuilder(command)
                .directory(request.projectDirectory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();

        Duration timeout = request.config().discoveryTimeout();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            JavaProcessSupport.terminateProcessTree(process, TERMINATION_GRACE);
            throw new IllegalStateException("ScenarioMesh discovery exceeded " + timeout + ". See " + log);
        }
        if (process.exitValue() != 0) {
            String detail = Files.exists(log) ? Files.readString(log) : "No discovery log was produced";
            throw new IllegalStateException("ScenarioMesh discovery failed with exit code " + process.exitValue()
                    + "." + System.lineSeparator() + detail);
        }

        return DiscoveryResultCodec.read(output);
    }
}
