package io.scenariomesh.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class InitApplier {
    void apply(InitPlan plan) throws Exception {
        List<InitPlan.FileChange> applied = new ArrayList<>();
        try {
            for (InitPlan.FileChange change : plan.changes()) {
                writeAtomically(change.path(), change.after());
                applied.add(change);
            }
        } catch (Exception failure) {
            rollback(applied, failure);
            throw failure;
        }
    }

    private void rollback(List<InitPlan.FileChange> applied, Exception original) {
        List<InitPlan.FileChange> reverse = new ArrayList<>(applied);
        Collections.reverse(reverse);
        for (InitPlan.FileChange change : reverse) {
            try {
                if (change.before() == null) {
                    Files.deleteIfExists(change.path());
                } else {
                    writeAtomically(change.path(), change.before());
                }
            } catch (Exception rollbackFailure) {
                original.addSuppressed(rollbackFailure);
            }
        }
    }

    private void writeAtomically(Path target, String content) throws Exception {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".scenariomesh-init-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
