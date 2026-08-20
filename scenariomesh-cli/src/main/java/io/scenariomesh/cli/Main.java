package io.scenariomesh.cli;

import java.util.Arrays;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        if (args.length == 0 || "doctor".equals(args[0])) {
            System.out.println("ScenarioMesh doctor");
            System.out.println("Java: " + System.getProperty("java.version"));
            System.out.println("OS: " + System.getProperty("os.name"));
            System.out.println("Use 'scenariomesh init' once, then keep using normal Maven commands.");
            return 0;
        }
        if ("init".equals(args[0])) {
            return new InitCommand().run(Arrays.copyOfRange(args, 1, args.length));
        }
        if ("version".equals(args[0]) || "--version".equals(args[0])) {
            System.out.println(ScenarioMeshVersion.current());
            return 0;
        }
        System.err.println("Unknown command: " + String.join(" ", args));
        return 2;
    }
}
