package io.scenariomesh.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class InitCommand {
    private final InitPlanner planner = new InitPlanner();
    private final InitApplier applier = new InitApplier();

    int run(String[] args) {
        try {
            Options options = Options.parse(args);
            String version = options.versionOverride() == null
                    ? ScenarioMeshVersion.current()
                    : options.versionOverride();
            InitPlan plan = planner.plan(options.projectDirectory(), version);
            printPlan(plan, version, options.dryRun());
            if (!options.dryRun() && !plan.empty()) {
                applier.apply(plan);
                System.out.println("ScenarioMesh init complete.");
            } else if (!options.dryRun() && plan.empty()) {
                System.out.println("ScenarioMesh is already initialized; no changes required.");
            }
            return 0;
        } catch (Exception exception) {
            System.err.println("ScenarioMesh init failed: " + exception.getMessage());
            return 2;
        }
    }

    private void printPlan(InitPlan plan, String version, boolean dryRun) {
        System.out.println("ScenarioMesh init" + (dryRun ? " (dry-run)" : ""));
        System.out.println("Project : " + plan.projectDirectory());
        System.out.println("Version : " + version);
        if (plan.empty()) {
            System.out.println("Plan    : no changes");
            return;
        }
        System.out.println("Plan:");
        for (InitPlan.FileChange change : plan.changes()) {
            Path relative = plan.projectDirectory().relativize(change.path());
            System.out.println("  " + change.kind() + " " + relative);
        }
    }

    private record Options(Path projectDirectory, boolean dryRun, String versionOverride) {
        static Options parse(String[] args) {
            Path project = Path.of(".");
            boolean dryRun = false;
            String version = null;
            List<String> positional = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--dry-run" -> dryRun = true;
                    case "--version" -> {
                        if (++i >= args.length) {
                            throw new IllegalArgumentException("--version requires a value");
                        }
                        version = args[i];
                    }
                    case "--project" -> {
                        if (++i >= args.length) {
                            throw new IllegalArgumentException("--project requires a path");
                        }
                        project = Path.of(args[i]);
                    }
                    default -> {
                        if (arg.startsWith("--")) {
                            throw new IllegalArgumentException("Unknown init option: " + arg);
                        }
                        positional.add(arg);
                    }
                }
            }
            if (positional.size() > 1) {
                throw new IllegalArgumentException("init accepts at most one project path");
            }
            if (!positional.isEmpty()) {
                project = Path.of(positional.get(0));
            }
            return new Options(project, dryRun, version);
        }
    }
}
