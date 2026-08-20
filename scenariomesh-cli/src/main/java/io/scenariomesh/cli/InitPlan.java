package io.scenariomesh.cli;

import java.nio.file.Path;
import java.util.List;

record InitPlan(Path projectDirectory, List<FileChange> changes) {
    InitPlan {
        projectDirectory = projectDirectory.toAbsolutePath().normalize();
        changes = List.copyOf(changes);
    }

    boolean empty() { return changes.isEmpty(); }

    record FileChange(Path path, String before, String after, ChangeKind kind) {
        FileChange { path = path.toAbsolutePath().normalize(); }
    }

    enum ChangeKind { CREATE, UPDATE }
}
