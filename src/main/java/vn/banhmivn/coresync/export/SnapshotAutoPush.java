package vn.banhmivn.coresync.export;

import org.bukkit.Bukkit;
import vn.banhmivn.coresync.BanhmiVNCoreSync;

/**
 * Tự động xuất + đẩy snapshot audit lên website định kỳ
 * (config {@code exports.auto-push-interval-hours}) — kể cả khi không ai chạy
 * {@code /bmvn exportaudit}, để website luôn có bản mới nhất cho staff tải.
 *
 * <p>Task chạy trên MAIN thread: export đọc {@code audit.log} đồng thời với
 * {@code AuditLogger} (cũng main thread) nên an toàn; phần HTTP push vẫn async
 * trong {@link BanhmiVNCoreSync#performSnapshotExport}. Tick chu kỳ lớn
 * (N giờ) — tác động tới vòng lặp server là không đáng kể.
 *
 * <p>Tắt khi: interval ≤ 0, push-to-website=false, hoặc api.key chưa cấu hình
 * (mỗi lần {@link #start()} log lý do).
 */
public final class SnapshotAutoPush implements Runnable {

    private final BanhmiVNCoreSync plugin;
    private int taskId = -1;

    public SnapshotAutoPush(BanhmiVNCoreSync plugin) {
        this.plugin = plugin;
    }

    /** Điều kiện bật auto-push (pure — unit-test được). */
    public static boolean isEnabled(int intervalHours, boolean pushToWebsite, boolean apiConfigured) {
        return intervalHours > 0 && pushToWebsite && apiConfigured;
    }

    /** Số tick Bukkit cho N giờ (20 tick/giây). */
    public static long ticksForHours(int hours) {
        return hours * 3600L * 20L;
    }

    /** Lên lịch task (nếu đủ điều kiện); gọi lại an toàn nhiều lần. */
    public void start() {
        if (taskId >= 0) {
            return; // đã chạy
        }
        int hours = plugin.pluginConfig().exportsAutoPushIntervalHours();
        boolean pushEnabled = plugin.pluginConfig().pushSnapshotsToWebsite();
        boolean apiConfigured = plugin.apiClient().isConfigured();
        if (!isEnabled(hours, pushEnabled, apiConfigured)) {
            plugin.getLogger().info("Auto-push snapshot tắt (auto-push-interval-hours=" + hours
                    + ", push-to-website=" + pushEnabled + ", api.key=" + apiConfigured + ").");
            return;
        }
        long period = ticksForHours(hours);
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this, period, period).getTaskId();
        plugin.getLogger().info("Auto-push snapshot: mỗi " + hours + " giờ"
                + " (lần đầu sau " + hours + " giờ, dùng /bmvn exportaudit để xuất ngay).");
    }

    /** Huỷ task (onDisable / reload). */
    public void stop() {
        if (taskId >= 0) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    @Override
    public void run() {
        // Main thread: export + đẩy; push HTTP chạy async, log console (không có chat).
        plugin.performSnapshotExport("AUTO_EXPORT", "scheduler", null);
    }
}
