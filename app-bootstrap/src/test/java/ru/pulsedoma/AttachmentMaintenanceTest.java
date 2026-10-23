package ru.pulsedoma;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import ru.pulsedoma.bootstrap.LocalAttachmentStore;
import ru.pulsedoma.issues.AttachmentMaintenance;

class AttachmentMaintenanceTest {
    @TempDir Path dir;

    @Test
    void cleanupRemovesOnlyUnreferencedObjectsAndIsRepeatable() {
        var db =
                new JdbcTemplate(
                        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("test.db")));
        db.execute("CREATE TABLE current_files(storage_key TEXT)");
        var store = new LocalAttachmentStore(dir.resolve("objects").toString());
        store.put("current/file", new byte[] {1}, "application/pdf");
        store.put("obsolete/file", new byte[] {2}, "application/pdf");
        db.update("INSERT INTO current_files VALUES ('current/file')");
        var cleanup = new AttachmentMaintenance(db, store);
        assertEquals(1, cleanup.prune().removed());
        assertEquals(List.of("current/file"), store.keys());
        assertArrayEquals(new byte[] {1}, store.get("current/file"));
        assertEquals(0, cleanup.prune().removed());
        assertThrows(IllegalArgumentException.class, () -> store.delete("../test.db"));
    }
}
