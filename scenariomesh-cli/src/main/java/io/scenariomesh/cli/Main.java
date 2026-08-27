package io.scenariomesh.cli;

import java.util.Arrays;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) {
        if (args.length == 0 || "doctor".equals(args[0])) {
            String[] doctorArgs = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
            return new DoctorCommand().run(doctorArgs);
        }
        if ("init".equals(args[0])) {
            return new InitCommand().run(Arrays.copyOfRange(args, 1, args.length));
        }
        if ("version".equals(args[0]) || "--version".equals(args[0])) {
            System.out.println(ScenarioMeshVersion.current());
            return 0;
        }
        System.err.println("Unknown command: " + String.join(" ", args));
        System.err.println("Commands: init, doctor [--deep] [--root PATH], version");
        return 2;
    }
}
