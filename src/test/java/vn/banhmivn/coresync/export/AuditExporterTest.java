package vn.banhmivn.coresync.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditExporterTest {

    @TempDir
    File tmp;

    private static final Logger LOG = Logger.getLogger(AuditExporterTest.class.getName());

    // ── Minimal tar READER (chỉ dùng trong test) ────────────

    private record TarEntry(String name, byte[] content) {
    }

    private static List<TarEntry> readTar(InputStream in) throws IOException {
        List<TarEntry> entries = new ArrayList<>();
        byte[] header = new byte[512];
        while (true) {
            readFully(in, header);
            if (allZero(header)) {
                break; // end-of-archive marker
            }
            String name = ascii(header, 0, 100).trim();
            if (name.isEmpty()) {
                break;
            }
            long size = Long.parseLong(ascii(header, 124, 12).trim(), 8);
            validateChecksum(header, name);

            byte[] content = new byte[(int) size];
            readFully(in, content);
            int pad = 512 - ((int) size % 512);
            if (pad < 512) {
                skipFully(in, pad);
            }
            entries.add(new TarEntry(name, content));
        }
        return entries;
    }

    private static void validateChecksum(byte[] header, String name) {
        int stored = (int) Long.parseLong(ascii(header, 148, 7).trim(), 8);
        byte[] copy = header.clone();
        for (int i = 148; i < 156; i++) {
            copy[i] = ' ';
        }
        int sum = 0;
        for (byte b : copy) {
            sum += b & 0xFF;
        }
        assertEquals(stored, sum, "checksum không hợp lệ cho entry " + name);
    }

    private static String ascii(byte[] buf, int off, int len) {
        return new String(buf, off, len, StandardCharsets.US_ASCII).replace("\0", "");
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) {
                throw new IOException("EOF giữa chừng khi đọc tar");
            }
            read += n;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long skipped = 0;
        while (skipped < n) {
            long s = in.skip(n - skipped);
            if (s <= 0) {
                throw new IOException("Không skip được tar padding");
            }
            skipped += s;
        }
    }

    private static boolean allZero(byte[] buf) {
        for (byte b : buf) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static List<TarEntry> readSnapshot(File gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz.toPath()))) {
            return readTar(in);
        }
    }

    private static String text(TarEntry e) {
        return new String(e.content(), StandardCharsets.UTF_8);
    }

    /** Dựng toàn bộ 6 file nguồn trong thư mục tạm. */
    private static void writeAllStateFiles(File dir) throws IOException {
        Files.writeString(new File(dir, "audit.log").toPath(),
                "[2026-08-08 10:00:00] REDEEM_OK player=Steve code=BMVN-AAAA-BBBB-CCCC\n");
        Files.writeString(new File(dir, "redeem-history.yml").toPath(),
                "history:\n  steve:\n    0:\n      code: BMVN-AAAA-BBBB-CCCC\n");
        Files.writeString(new File(dir, "used-codes.yml").toPath(),
                "used:\n  BMVN-AAAA-BBBB-CCCC:\n    player: Steve\n");
        Files.writeString(new File(dir, "pending-rewards.yml").toPath(),
                "pending:\n  notch:\n    0:\n      type: point\n");
        Files.writeString(new File(dir, "items.yml").toPath(),
                "items:\n  crate:premium:\n    item: rO0ABXNyAApCdWtr\n");
    }

    private static AuditExporter.ExportSources sources(File dir) {
        return new AuditExporter.ExportSources(
                new File(dir, "audit.log"),
                new File(dir, "audit.log.1"),
                new File(dir, "redeem-history.yml"),
                new File(dir, "used-codes.yml"),
                new File(dir, "pending-rewards.yml"),
                new File(dir, "items.yml"));
    }

    // ── Tests ───────────────────────────────────────────────

    @Test
    void tarWriterRoundTripsWithValidChecksum() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TarWriter tar = new TarWriter(out);
        tar.writeEntry("a.txt", "hello".getBytes(StandardCharsets.UTF_8), 1_700_000_000L);
        tar.writeEntry("sub/b.txt", "x".repeat(600).getBytes(StandardCharsets.UTF_8), 1_700_000_001L);
        tar.finish();

        List<TarEntry> entries = readTar(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(2, entries.size());
        assertEquals("a.txt", entries.get(0).name());
        assertEquals("hello", text(entries.get(0)));
        assertEquals("sub/b.txt", entries.get(1).name());
        assertEquals(600, entries.get(1).content().length);
    }

    @Test
    void exportCapturesFullPluginState() throws IOException {
        writeAllStateFiles(tmp);

        AuditExporter.SnapshotResult result = new AuditExporter(LOG)
                .export(tmp, sources(tmp), "Test Server", "1.0.0");

        assertTrue(result.file().getName().matches("audit-snapshot-\\d{8}-\\d{6}\\.tar\\.gz"),
                result.file().getName());
        assertTrue(result.file().exists());
        assertTrue(result.bytes() > 0);

        List<TarEntry> entries = readSnapshot(result.file());
        // MANIFEST.txt + 5 file trạng thái (audit.log.1 chưa tồn tại nên không có)
        assertEquals(6, entries.size());
        assertEquals(6, result.entries(), "counter dùng cho chat message / EXPORT line phải khớp");

        for (String name : List.of("audit.log", "redeem-history.yml", "used-codes.yml",
                "pending-rewards.yml", "items.yml")) {
            TarEntry e = entries.stream().filter(x -> x.name().equals(name))
                    .findFirst().orElseThrow(() -> new AssertionError("thiếu entry " + name));
            assertEquals(Files.readString(new File(tmp, name).toPath()), text(e), name);
        }
        assertTrue(entries.stream().noneMatch(e -> e.name().equals("audit-1.log")));

        String m = text(entries.stream().filter(e -> e.name().equals("MANIFEST.txt"))
                .findFirst().orElseThrow());
        assertTrue(m.contains("Test Server"));
        assertTrue(m.contains("1.0.0"));
        // Danh sách file kèm size thực (chỉ xuất hiện trong phần liệt kê, không phải footer)
        for (String name : List.of("audit.log", "redeem-history.yml", "used-codes.yml",
                "pending-rewards.yml", "items.yml")) {
            long len = new File(tmp, name).length();
            assertTrue(m.contains(name) && m.contains(len + " bytes"), name + " thiếu trong manifest");
        }
    }

    @Test
    void exportIncludesRotatedAuditLog() throws IOException {
        File audit = new File(tmp, "audit.log");
        File rotated = new File(tmp, "audit.log.1");
        Files.writeString(audit.toPath(), "current\n");
        Files.writeString(rotated.toPath(), "old-lines\n");

        AuditExporter.SnapshotResult result = new AuditExporter(LOG)
                .export(tmp, sources(tmp), "S", "1.0.0");

        List<TarEntry> entries = readSnapshot(result.file());
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("audit-1.log")),
                "snapshot phải chứa audit-1.log");
        assertEquals("old-lines\n", text(entries.stream()
                .filter(e -> e.name().equals("audit-1.log")).findFirst().orElseThrow()));
    }

    @Test
    void missingFilesStillProduceSnapshot() throws IOException {
        File audit = new File(tmp, "audit.log");
        Files.writeString(audit.toPath(), "only-audit\n");

        AuditExporter.SnapshotResult result = new AuditExporter(LOG)
                .export(tmp, sources(tmp), "S", "1.0.0");

        List<TarEntry> entries = readSnapshot(result.file());
        assertEquals(2, entries.size()); // MANIFEST.txt + audit.log
        assertTrue(entries.stream().noneMatch(e -> e.name().equals("redeem-history.yml")));
        assertTrue(entries.stream().noneMatch(e -> e.name().equals("items.yml")));
        assertEquals("only-audit\n", text(entries.stream()
                .filter(e -> e.name().equals("audit.log")).findFirst().orElseThrow()));
    }

    @Test
    void exportsDirIsCreatedAndSnapshotIsValidGzip() throws IOException {
        writeAllStateFiles(tmp);
        new AuditExporter(LOG).export(tmp, sources(tmp), "S", "1.0.0");

        File exportsDir = new File(tmp, "exports");
        assertTrue(exportsDir.isDirectory());
        File[] files = exportsDir.listFiles((d, n) -> n.endsWith(".tar.gz"));
        assertTrue(files != null && files.length >= 1);
        // gunzip thành công chứng tỏ gzip hợp lệ
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(files[0].toPath()))) {
            assertFalse(allZero(in.readNBytes(512)));
        }
    }

    @Test
    void snapshotContentSurvivesRepeatedExport() throws IOException {
        writeAllStateFiles(tmp);
        AuditExporter exporter = new AuditExporter(LOG);
        exporter.export(tmp, sources(tmp), "S", "1.0.0");
        AuditExporter.SnapshotResult second = exporter.export(tmp, sources(tmp), "S", "1.0.0");

        List<TarEntry> entries = readSnapshot(second.file());
        TarEntry auditEntry = entries.stream().filter(e -> e.name().equals("audit.log"))
                .findFirst().orElseThrow();
        assertEquals(Files.readString(new File(tmp, "audit.log").toPath()), text(auditEntry));
    }
}
