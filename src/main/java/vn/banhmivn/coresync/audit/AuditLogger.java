package vn.banhmivn.coresync.audit;

import org.bukkit.plugin.Plugin;
import vn.banhmivn.coresync.api.dto.CodeItem;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Audit trail — ghi MỌI sự kiện sinh/đổi giftcode vào file append-only
 * {@code audit.log} (một dòng / sự kiện, có timestamp):
 *
 * <pre>
 * [2026-08-08 14:03:12] REDEEM_OK        player=Steve          code=BMVN-XXXX-XXXX-XXXX items=[rank x1 (👑 Rank VIP+)] order=DHV260808123456ABC
 * [2026-08-08 14:03:12] REDEEM_INVALID   player=Steve          code=BMVN-....           items=[] bad-format
 * [2026-08-08 14:05:00] GENERATE         player=Notch          code=BMVN-YYYY-YYYY-YYYY items=[point x500 (💎 Đổi Point Server)]
 * [2026-08-08 14:05:01] SYNC_OK          player=Notch          code=BMVN-YYYY-YYYY-YYYY items=[] order_id=123
 * </pre>
 *
 * <p>Toàn bộ write xảy ra trên main thread (redeem/generate đều chạy main thread);
 * phương thức {@code log} được {@code synchronized} phòng trường hợp tương lai
 * gọi từ thread khác.
 */
public class AuditLogger {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Quay vòng audit.log khi vượt 5MB (rename → audit.log.1, xoá bản cũ hơn). */
    private static final long MAX_AUDIT_BYTES = 5L * 1024 * 1024;

    private final Plugin plugin;
    private final File file;

    public AuditLogger(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "audit.log");
    }

    /** Ghi một dòng audit. Tất cả field được làm sạch ký tự xuống dòng (chống log injection). */
    public synchronized void log(String event, String player, String code, String items, String detail) {
        rotateIfNeeded();
        String line = String.format("[%s] %-16s player=%-16s code=%-19s items=[%s] %s",
                LocalDateTime.now().format(TS),
                safe(event), safe(player), safe(code), safe(items), safe(detail));
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line + System.lineSeparator());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Không ghi được audit.log", ex);
        }
    }

    /** Quay vòng khi audit.log quá lớn — chống phình vô hạn theo thời gian. */
    private void rotateIfNeeded() {
        if (file.exists() && file.length() > MAX_AUDIT_BYTES) {
            File rotated = new File(file.getParentFile(), "audit.log.1");
            if (rotated.exists() && !rotated.delete()) {
                plugin.getLogger().warning("Không xoá được audit.log.1 cũ");
            }
            if (file.renameTo(rotated)) {
                plugin.getLogger().info("Audit log đã quay vòng → " + rotated.getName());
            }
        }
    }

    // ── Sự kiện redeem ──────────────────────────────────────

    public void logRedeemOk(String player, String code, List<CodeItem> items, String detail) {
        log("REDEEM_OK", player, code, itemsToString(items), detail);
    }

    public void logRedeemAlreadyUsed(String player, String code) {
        log("REDEEM_USED", player, code, "", "already-used");
    }

    public void logRedeemInvalid(String player, String code, String reason) {
        log("REDEEM_INVALID", player, code, "", reason);
    }

    public void logRedeemFail(String player, String code, String detail) {
        log("REDEEM_FAIL", player, code, "", detail);
    }

    // ── Sự kiện generate + sync ─────────────────────────────

    public void logGenerate(String actor, String code, List<CodeItem> items) {
        log("GENERATE", actor, code, itemsToString(items), "");
    }

    public void logSyncOk(String code, Long orderId) {
        log("SYNC_OK", "-", code, "", "order_id=" + (orderId == null ? "-" : orderId));
    }

    public void logSyncFail(String code, String detail) {
        log("SYNC_FAIL", "-", code, "", detail);
    }

    // ── Helpers ─────────────────────────────────────────────

    /** Chuỗi mô tả gọn của items: {@code rank x1 (👑 Rank VIP+), point x500 (...)}. */
    public static String itemsToString(List<CodeItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CodeItem it : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(it.getProductType()).append(" x").append(it.getQty())
                    .append(" (").append(it.getProductName()).append(')');
        }
        return sb.toString();
    }

    /** Làm sạch chuỗi trước khi ghi log — chống newline/control-char injection. */
    private static String safe(String s) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    public File file() {
        return file;
    }
}
