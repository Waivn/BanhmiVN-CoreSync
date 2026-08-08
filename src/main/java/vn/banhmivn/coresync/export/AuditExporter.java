package vn.banhmivn.coresync.export;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * Xuất snapshot audit để bàn giao cho admin: nén {@code audit.log} (+ bản đã
 * quay vòng {@code audit.log.1} nếu có) và {@code redeem-history.yml} thành
 * một file {@code .tar.gz} duy nhất trong {@code exports/}.
 *
 * <p>Thuần Java (không phụ thuộc Bukkit) — unit-test được. Chạy trên main
 * thread: đọc/gzip vài MB log là việc nhanh (&lt; 200ms) và tránh mọi tranh
 * chấp đọc/ghi với AuditLogger (vốn cũng chạy main thread).
 *
 * <p>Format: tar ustar (tương thích 7-Zip/WinRAR/tar) qua GZIP. Kèm
 * {@code MANIFEST.txt} ghi thời điểm, server, version và dung lượng từng file.
 */
public class AuditExporter {

    /** Kết quả xuất snapshot. */
    public record SnapshotResult(File file, long bytes, int entries) {
    }

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Logger logger;

    public AuditExporter(Logger logger) {
        this.logger = logger;
    }

    /**
     * Tạo snapshot gzipped từ các file audit.
     *
     * @param dataFolder    thư mục dữ liệu plugin (để tạo {@code exports/})
     * @param auditLog      file {@code audit.log} (bắt buộc — có thể chưa tồn tại)
     * @param auditLogOld   file {@code audit.log.1} đã quay vòng (nullable)
     * @param historyFile   file {@code redeem-history.yml} (nullable)
     * @param serverName    tên server (ghi vào manifest)
     * @param pluginVersion version plugin (ghi vào manifest)
     */
    public SnapshotResult export(File dataFolder, File auditLog, File auditLogOld,
                                 File historyFile, String serverName, String pluginVersion)
            throws IOException {
        File exportsDir = new File(dataFolder, "exports");
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            throw new IOException("Không tạo được thư mục exports/");
        }

        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(STAMP);
        File target = new File(exportsDir, "audit-snapshot-" + stamp + ".tar.gz");
        File tmp = File.createTempFile("audit-snapshot-", ".tmp", exportsDir);

        int entries = 0;
        long nowSecs = System.currentTimeMillis() / 1000;
        try {
            try (GZIPOutputStream gz = new GZIPOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp)))) {
                TarWriter tar = new TarWriter(gz);
                ManifestBuilder manifest = new ManifestBuilder(serverName, pluginVersion, stamp);

                // Ghi các entry dữ liệu TRƯỚC, rồi mới build + ghi MANIFEST (cần biết size từng file).
                if (auditLog.exists()) {
                    tar.writeEntry("audit.log", Files.readAllBytes(auditLog.toPath()),
                            auditLog.lastModified() / 1000);
                    manifest.addFile("audit.log", auditLog.length());
                    entries++;
                } else {
                    logger.warning("Không tìm thấy audit.log — snapshot thiếu trail thô.");
                }
                if (auditLogOld != null && auditLogOld.exists()) {
                    tar.writeEntry("audit-1.log", Files.readAllBytes(auditLogOld.toPath()),
                            auditLogOld.lastModified() / 1000);
                    manifest.addFile("audit-1.log", auditLogOld.length());
                    entries++;
                }
                if (historyFile != null && historyFile.exists()) {
                    tar.writeEntry("redeem-history.yml", Files.readAllBytes(historyFile.toPath()),
                            historyFile.lastModified() / 1000);
                    manifest.addFile("redeem-history.yml", historyFile.length());
                    entries++;
                } else {
                    logger.warning("Không tìm thấy redeem-history.yml — snapshot thiếu lịch sử redeem.");
                }

                tar.writeEntry("MANIFEST.txt",
                        manifest.build().getBytes(StandardCharsets.UTF_8), nowSecs);
                entries++;
                tar.finish();
            }

            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // Dọn file tạm nếu thất bại giữa chừng (tránh rò rỉ .tmp trong exports/)
            if (tmp.exists() && !tmp.delete()) {
                logger.warning("Không xoá được file tạm: " + tmp.getName());
            }
        }
        logger.info("Đã xuất snapshot audit: " + target.getName()
                + " (" + target.length() + " bytes, " + entries + " entries)");
        return new SnapshotResult(target, target.length(), entries);
    }

    /** Dựng nội dung MANIFEST.txt trong archive. */
    private static final class ManifestBuilder {
        private final StringBuilder sb = new StringBuilder();
        private final String serverName;
        private final String pluginVersion;
        private final String stamp;

        ManifestBuilder(String serverName, String pluginVersion, String stamp) {
            this.serverName = serverName == null ? "?" : serverName;
            this.pluginVersion = pluginVersion == null ? "?" : pluginVersion;
            this.stamp = stamp;
        }

        ManifestBuilder addFile(String name, long bytes) {
            sb.append(String.format("%-22s %10d bytes%n", name, bytes));
            return this;
        }

        String build() {
            return "BanhmiVN-CoreSync — audit snapshot\n"
                    + "=================================\n"
                    + "Export time (UTC): " + stamp + "\n"
                    + "Server:            " + serverName + "\n"
                    + "Plugin version:    " + pluginVersion + "\n"
                    + "\n"
                    + "Files (UTF-8 text):\n"
                    + sb
                    + "\n"
                    + "audit.log lines: [ts] EVENT player=... code=... items=[...] detail\n"
                    + "redeem-history.yml: player -> list of redeemed codes.\n";
        }
    }
}
