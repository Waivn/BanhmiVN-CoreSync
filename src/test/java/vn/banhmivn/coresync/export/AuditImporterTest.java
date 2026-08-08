package vn.banhmivn.coresync.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditImporterTest {

    @TempDir
    File tmp;

    private static final Logger LOG = Logger.getLogger(AuditImporterTest.class.getName());

    /** Ghi một snapshot .tar.gz tùy biến vào exports/ (dùng TarWriter — checksum hợp lệ). */
    private File writeSnapshot(String name, Map<String, String> entries) throws IOException {
        File dir = new File(tmp, "exports");
        assertTrue(dir.mkdirs());
        File file = new File(dir, name);
        try (GZIPOutputStream gz = new GZIPOutputStream(new FileOutputStream(file))) {
            TarWriter tar = new TarWriter(gz);
            long now = 1_700_000_000L;
            for (Map.Entry<String, String> e : entries.entrySet()) {
                tar.writeEntry(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8), now);
            }
            tar.finish();
        }
        return file;
    }

    private static String read(File f) throws IOException {
        return Files.readString(f.toPath());
    }

    // ── Round-trip: export → import ─────────────────────────

    @Test
    void exportThenImportRestoresFullState() throws IOException {
        File src = new File(tmp, "src");
        assertTrue(src.mkdirs());
        Files.writeString(new File(src, "audit.log").toPath(), "[ts] REDEEM_OK player=Steve\n");
        Files.writeString(new File(src, "audit.log.1").toPath(), "rotated-lines\n");
        Files.writeString(new File(src, "redeem-history.yml").toPath(), "history:\n  steve:\n    0:\n      code: X\n");
        Files.writeString(new File(src, "used-codes.yml").toPath(), "used:\n  BMVN-X:\n    player: Steve\n");
        Files.writeString(new File(src, "pending-rewards.yml").toPath(), "pending:\n  notch:\n    0:\n      type: point\n");
        Files.writeString(new File(src, "items.yml").toPath(), "items:\n  crate:premium:\n    item: base64\n");

        AuditExporter.SnapshotResult export = new AuditExporter(LOG).export(
                src,
                new AuditExporter.ExportSources(
                        new File(src, "audit.log"),
                        new File(src, "audit.log.1"),
                        new File(src, "redeem-history.yml"),
                        new File(src, "used-codes.yml"),
                        new File(src, "pending-rewards.yml"),
                        new File(src, "items.yml")),
                "S", "1.0.0", 0);

        // Bàn giao thật: copy snapshot sang exports/ của server mới rồi import
        File dst = new File(tmp, "dst");
        File dstExports = new File(dst, "exports");
        assertTrue(dstExports.mkdirs());
        Files.copy(export.file().toPath(),
                new File(dstExports, export.file().getName()).toPath());
        AuditImporter.ImportResult result = new AuditImporter(LOG)
                .importSnapshot(dst, export.file().getName());

        assertEquals(List.of("audit.log", "audit.log.1", "redeem-history.yml",
                "used-codes.yml", "pending-rewards.yml", "items.yml"), result.restored());
        assertArrayEquals(Files.readAllBytes(new File(src, "audit.log").toPath()),
                Files.readAllBytes(new File(dst, "audit.log").toPath()));
        // audit-1.log trong archive → audit.log.1 trên disk
        assertArrayEquals(Files.readAllBytes(new File(src, "audit.log.1").toPath()),
                Files.readAllBytes(new File(dst, "audit.log.1").toPath()));
        assertArrayEquals(Files.readAllBytes(new File(src, "items.yml").toPath()),
                Files.readAllBytes(new File(dst, "items.yml").toPath()));
    }

    // ── Security: path traversal & unknown entries ──────────

    @Test
    void rejectsPathTraversalEntryAndWritesNothing() throws IOException {
        writeSnapshot("evil.tar.gz", Map.of("../config.yml", "evil"));

        assertThrows(IOException.class, () ->
                new AuditImporter(LOG).importSnapshot(tmp, "evil.tar.gz"));
        // Không ghi file nào vào dataFolder (chỉ có exports/)
        File[] files = tmp.listFiles();
        assertEquals(1, files.length);
        assertEquals("exports", files[0].getName());
    }

    @Test
    void rejectsAbsolutePathEntry() throws IOException {
        writeSnapshot("evil2.tar.gz", Map.of("/etc/passwd", "evil"));
        assertThrows(IOException.class, () ->
                new AuditImporter(LOG).importSnapshot(tmp, "evil2.tar.gz"));
    }

    @Test
    void rejectsUnknownEntryAndWritesNothing() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("audit.log", "legit");
        entries.put("config.yml", "evil"); // ngoài whitelist
        writeSnapshot("mixed.tar.gz", entries);

        assertThrows(IOException.class, () ->
                new AuditImporter(LOG).importSnapshot(tmp, "mixed.tar.gz"));
        // Không có partial write: audit.log không được ghi dù xuất hiện trước
        assertFalse(new File(tmp, "audit.log").exists());
    }

    @Test
    void rejectsCorruptChecksum() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        TarWriter tar = new TarWriter(raw);
        tar.writeEntry("audit.log", "hello".getBytes(StandardCharsets.UTF_8), 1_700_000_000L);
        tar.finish();
        byte[] bytes = raw.toByteArray();
        bytes[300] ^= 0x01; // hỏng 1 byte trong header block → checksum lệch

        File dir = new File(tmp, "exports");
        assertTrue(dir.mkdirs());
        File corrupt = new File(dir, "corrupt.tar.gz");
        try (GZIPOutputStream gz = new GZIPOutputStream(new FileOutputStream(corrupt))) {
            gz.write(bytes);
        }

        assertThrows(IOException.class, () ->
                new AuditImporter(LOG).importSnapshot(tmp, "corrupt.tar.gz"));
        assertFalse(new File(tmp, "audit.log").exists());
    }

    // ── Filename / existence validation ─────────────────────

    @Test
    void rejectsFileNameWithPathSeparator() throws IOException {
        writeSnapshot("ok.tar.gz", Map.of());
        assertThrows(IOException.class, () ->
                new AuditImporter(LOG).importSnapshot(tmp, "../other.tar.gz"));
        assertThrows(IOException.class, () ->
                new AuditImporter(LOG).importSnapshot(tmp, "sub\\x.tar.gz"));
    }

    @Test
    void rejectsMissingSnapshot() {
        assertThrows(IOException.class, () ->
                new AuditImporter(LOG).importSnapshot(tmp, "nope.tar.gz"));
    }

    @Test
    void previewListsFilesWithoutWriting() throws IOException {
        writeSnapshot("preview.tar.gz",
                Map.of("audit.log", "x", "items.yml", "y", "MANIFEST.txt", "meta"));

        AuditImporter.ImportResult preview = new AuditImporter(LOG)
                .previewSnapshot(tmp, "preview.tar.gz");

        assertEquals(List.of("audit.log", "items.yml"), preview.restored());
        // Preview KHÔNG được ghi gì vào disk
        assertFalse(new File(tmp, "audit.log").exists());
        assertFalse(new File(tmp, "items.yml").exists());
    }

    @Test
    void onlyManifestGivesEmptyRestore() throws IOException {
        writeSnapshot("meta-only.tar.gz", Map.of("MANIFEST.txt", "metadata"));
        AuditImporter.ImportResult result = new AuditImporter(LOG)
                .importSnapshot(tmp, "meta-only.tar.gz");
        assertTrue(result.restored().isEmpty());
        assertFalse(new File(tmp, "audit.log").exists());
    }
}
