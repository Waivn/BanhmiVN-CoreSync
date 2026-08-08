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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * Xuất snapshot toàn bộ trạng thái plugin để bàn giao cho admin: nén
 * {@code audit.log} (+ bản đã quay vòng {@code audit.log.1}), {@code redeem-history.yml},
 * {@code used-codes.yml}, {@code pending-rewards.yml} và {@code items.yml} thành
 * một file {@code .tar.gz} duy nhất trong {@code exports/}.
 *
 * <p>Thuần Java (không phụ thuộc Bukkit) — unit-test được. Chạy trên main
 * thread: đọc/gzip vài MB log là việc nhanh (&lt; 200ms) và tránh mọi tranh
 * chấp đọc/ghi với các store (vốn cũng chạy main thread).
 *
 * <p>Format: tar ustar (tương thích 7-Zip/WinRAR/tar) qua GZIP. Kèm
 * {@code MANIFEST.txt} ghi thời điểm, server, version và dung lượng từng file.
 */
public class AuditExporter {

    /** Kết quả xuất snapshot. */
    public record SnapshotResult(File file, long bytes, int entries, int pruned) {
    }

    /** Các file nguồn cần đóng gói (một số có thể chưa tồn tại — bỏ qua kèm cảnh báo). */
    public record ExportSources(
            File auditLog,
            File auditLogOld,
            File redeemHistory,
            File usedCodes,
            File pendingRewards,
            File items) {
    }

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private record FileSpec(String name, File file) {
    }

    private final Logger logger;

    public AuditExporter(Logger logger) {
        this.logger = logger;
    }

    /**
     * Tạo snapshot gzipped từ toàn bộ trạng thái plugin.
     *
     * @param dataFolder    thư mục dữ liệu plugin (để tạo {@code exports/})
     * @param sources       các file nguồn (file chưa tồn tại được bỏ qua)
     * @param serverName    tên server (ghi vào manifest)
     * @param pluginVersion version plugin (ghi vào manifest)
     * @param retentionDays số ngày giữ snapshot cũ trong exports/ (≤ 0 = không dọn dẹp)
     */
    public SnapshotResult export(File dataFolder, ExportSources sources,
                                 String serverName, String pluginVersion,
                                 int retentionDays) throws IOException {
        File exportsDir = new File(dataFolder, "exports");
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            throw new IOException("Không tạo được thư mục exports/");
        }

        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(STAMP);
        File target = new File(exportsDir, "audit-snapshot-" + stamp + ".tar.gz");
        File tmp = File.createTempFile("audit-snapshot-", ".tmp", exportsDir);

        int entries = 0;
        long nowSecs = System.currentTimeMillis() / 1000;
        List<String> missing = new ArrayList<>();
        try {
            try (GZIPOutputStream gz = new GZIPOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp)))) {
                TarWriter tar = new TarWriter(gz);
                ManifestBuilder manifest = new ManifestBuilder(serverName, pluginVersion, stamp);

                for (FileSpec spec : fileSpecs(sources)) {
                    File file = spec.file();
                    if (file == null || !file.exists()) {
                        missing.add(spec.name());
                        continue;
                    }
                    tar.writeEntry(spec.name(), Files.readAllBytes(file.toPath()),
                            file.lastModified() / 1000);
                    manifest.addFile(spec.name(), file.length());
                    entries++;
                }

                // MANIFEST viết sau cùng (cần biết size từng file).
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

        if (!missing.isEmpty()) {
            logger.warning("Snapshot thiếu " + missing.size() + " file (chưa tồn tại): "
                    + String.join(", ", missing));
        }
        logger.info("Đã xuất snapshot: " + target.getName()
                + " (" + target.length() + " bytes, " + entries + " entries)");
        int pruned = pruneExports(dataFolder, retentionDays);
        return new SnapshotResult(target, target.length(), entries, pruned);
    }

    /**
     * Xoá snapshot cũ hơn {@code retentionDays} ngày trong {@code exports/}.
     * Chỉ đụng vào file {@code audit-snapshot-*.tar.gz}; file khác (kể cả file
     * tạm, file admin bỏ vào) được giữ nguyên. ≤ 0 ngày = tắt. Trả về số file đã xoá.
     */
    public int pruneExports(File dataFolder, int retentionDays) {
        if (retentionDays <= 0) {
            return 0;
        }
        File exportsDir = new File(dataFolder, "exports");
        if (!exportsDir.isDirectory()) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600 * 1000;
        File[] snapshots = exportsDir.listFiles((dir, name) ->
                name.startsWith("audit-snapshot-") && name.endsWith(".tar.gz"));
        if (snapshots == null) {
            return 0;
        }
        int removed = 0;
        for (File snapshot : snapshots) {
            long lastModified = snapshot.lastModified();
            // lastModified() <= 0 = không đọc được mtime → không biết tuổi → GIỮ NGUYÊN
            if (lastModified <= 0 || lastModified >= cutoff) {
                continue;
            }
            if (snapshot.delete()) {
                removed++;
            } else {
                logger.warning("Không xoá được snapshot cũ: " + snapshot.getName());
            }
        }
        if (removed > 0) {
            logger.info("Đã dọn " + removed + " snapshot cũ (quá " + retentionDays + " ngày).");
        }
        return removed;
    }

    private static List<FileSpec> fileSpecs(ExportSources s) {
        return List.of(
                new FileSpec("audit.log", s.auditLog()),
                new FileSpec("audit-1.log", s.auditLogOld()),
                new FileSpec("redeem-history.yml", s.redeemHistory()),
                new FileSpec("used-codes.yml", s.usedCodes()),
                new FileSpec("pending-rewards.yml", s.pendingRewards()),
                new FileSpec("items.yml", s.items()));
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
            return "BanhmiVN-CoreSync — full state snapshot\n"
                    + "=======================================\n"
                    + "Export time (UTC): " + stamp + "\n"
                    + "Server:            " + serverName + "\n"
                    + "Plugin version:    " + pluginVersion + "\n"
                    + "\n"
                    + "Files (UTF-8 text):\n"
                    + sb
                    + "\n"
                    + "audit.log:          [ts] EVENT player=... code=... items=[...] detail\n"
                    + "redeem-history.yml: player -> list of redeemed codes\n"
                    + "used-codes.yml:     codes marked used locally (chống dùng lại)\n"
                    + "pending-rewards.yml: rewards chưa trao được (tự trao lại khi vào server)\n"
                    + "items.yml:          items đã bind (base64 ItemStack đầy đủ NBT/meta)\n";
        }
    }
}
