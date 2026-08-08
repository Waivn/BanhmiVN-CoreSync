package vn.banhmivn.coresync.export;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Khôi phục snapshot ({@code /bmvn importaudit <file>}): đọc file {@code .tar.gz}
 * trong {@code exports/} và ghi các file trạng thái đã biết trở lại thư mục
 * plugin — hoàn tất vòng bàn giao (export → restore trên server mới).
 *
 * <p>An toàn theo thiết kế:
 * <ul>
 *   <li>Chỉ nhận tên file phẳng, đuôi {@code .tar.gz} (chống path traversal).</li>
 *   <li>Chỉ khôi phục đúng 6 file trạng thái plugin biết (whitelist); entry lạ
 *       → <b>ABORT trước khi ghi bất cứ thứ gì</b> (không bao giờ đè config.yml...).</li>
 *   <li>{@link TarReader} xác thực checksum + giới hạn kích thước trước khi ghi.</li>
 *   <li>Mỗi file ghi qua temp + rename (atomic per-file).</li>
 * </ul>
 *
 * <p>Lưu ý: sau khi import, tầng điều phối phải gọi {@code reload()} lên các
 * store để bộ nhớ khớp với disk (xem {@code BanhmiVNCoreSync.reloadStores}).
 */
public class AuditImporter {

    /** Kết quả khôi phục. */
    public record ImportResult(List<String> restored, int totalEntries) {
    }

    /** Entry trong snapshot → tên file khi ghi lại vào thư mục plugin. */
    private static final Map<String, String> RESTORE_MAP = createRestoreMap();

    private static Map<String, String> createRestoreMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("audit.log", "audit.log");
        map.put("audit-1.log", "audit.log.1");
        map.put("redeem-history.yml", "redeem-history.yml");
        map.put("used-codes.yml", "used-codes.yml");
        map.put("pending-rewards.yml", "pending-rewards.yml");
        map.put("items.yml", "items.yml");
        return map;
    }

    /** Entry chỉ đọc (metadata) — không phải file khôi phục. */
    private static final List<String> SKIP = List.of("MANIFEST.txt");

    private final Logger logger;

    public AuditImporter(Logger logger) {
        this.logger = logger;
    }

    /**
     * Xem trước: đọc + xác thực snapshot, trả về danh sách file sẽ khôi phục
     * (KHÔNG ghi gì vào disk) — dùng cho bước xác nhận trước khi import.
     *
     * @throws IOException tên file sai, không tìm thấy, archive hỏng, entry lạ
     */
    public ImportResult previewSnapshot(File dataFolder, String fileName) throws IOException {
        Map<String, byte[]> entries = readAndValidate(dataFolder, fileName);
        List<String> restorable = new ArrayList<>();
        for (Map.Entry<String, String> e : RESTORE_MAP.entrySet()) {
            if (entries.containsKey(e.getKey())) {
                restorable.add(e.getValue());
            }
        }
        return new ImportResult(restorable, entries.size());
    }

    /**
     * Khôi phục snapshot từ {@code exports/<fileName>} vào {@code dataFolder}.
     *
     * @throws IOException tên file sai, không tìm thấy, archive hỏng, entry lạ
     *                     (không ghi gì trong mọi trường hợp lỗi)
     */
    public ImportResult importSnapshot(File dataFolder, String fileName) throws IOException {
        Map<String, byte[]> entries = readAndValidate(dataFolder, fileName);

        // Ghi từng file đã biết: temp + rename atomic.
        List<String> restored = new ArrayList<>();
        for (Map.Entry<String, String> e : RESTORE_MAP.entrySet()) {
            byte[] content = entries.get(e.getKey());
            if (content == null) {
                continue;
            }
            File target = new File(dataFolder, e.getValue());
            writeAtomic(target, content);
            restored.add(e.getValue());
            logger.info("Đã khôi phục " + e.getValue() + " (" + content.length + " bytes)");
        }
        return new ImportResult(restored, entries.size());
    }

    /** Đọc + xác thực toàn bộ archive; chưa ghi gì. */
    private Map<String, byte[]> readAndValidate(File dataFolder, String fileName) throws IOException {
        if (fileName == null || fileName.isBlank()) {
            throw new IOException("Thiếu tên file snapshot.");
        }
        if (fileName.contains("/") || fileName.contains("\\") || !fileName.endsWith(".tar.gz")) {
            throw new IOException("Tên file không hợp lệ (chỉ nhận tên file .tar.gz trong exports/).");
        }
        File snapshot = new File(new File(dataFolder, "exports"), fileName);
        if (!snapshot.isFile()) {
            throw new IOException("Không tìm thấy snapshot: exports/" + fileName);
        }

        Map<String, byte[]> entries;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(snapshot.toPath()))) {
            entries = new TarReader().read(in);
        }

        // Có entry lạ (không thuộc whitelist, không phải MANIFEST) → từ chối toàn bộ.
        for (String name : entries.keySet()) {
            if (!RESTORE_MAP.containsKey(name) && !SKIP.contains(name)) {
                throw new IOException("Snapshot chứa entry không xác định '" + name
                        + "' — từ chối khôi phục (không phải snapshot của plugin?).");
            }
        }
        return entries;
    }

    private void writeAtomic(File target, byte[] content) throws IOException {
        File tmp = File.createTempFile(target.getName() + "-import-", ".tmp",
                target.getParentFile());
        try {
            Files.write(tmp.toPath(), content);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                logger.warning("Không xoá được file tạm: " + tmp.getName());
            }
        }
    }
}
