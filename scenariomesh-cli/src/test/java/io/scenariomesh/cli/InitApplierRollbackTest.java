package io.scenariomesh.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InitApplierRollbackTest {
    @TempDir Path temp;

    @Test
    void secondWriteFailureRemovesFirstCreatedFile() throws Exception {
        Path first = temp.resolve(".mvn/extensions.xml");
        Path second = temp.resolve("scenariomesh.yml");
        InitPlan plan = new InitPlan(temp, java.util.List.of(
                new InitPlan.FileChange(first, null, "extensions", InitPlan.ChangeKind.CREATE),
                new InitPlan.FileChange(second, null, "config", InitPlan.ChangeKind.CREATE)));

        AtomicInteger writes = new AtomicInteger();
        InitApplier.FileWriter writer = (target, content) -> {
            if (writes.incrementAndGet() == 2) {
                throw new IllegalStateException("simulated second write failure");
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        };

        assertThrows(IllegalStateException.class, () -> new InitApplier(writer).apply(plan));
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
    }
}
