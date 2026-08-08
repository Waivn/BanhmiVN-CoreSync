package vn.banhmivn.coresync.heartbeat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import vn.banhmivn.coresync.ServerState;
import vn.banhmivn.coresync.api.ApiClient;
import vn.banhmivn.coresync.api.dto.ServerStatusPayload;
import vn.banhmivn.coresync.config.PluginConfig;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Heartbeat service — cứ mỗi {@code server.heartbeat-interval-seconds} (mặc định 15s)
 * đẩy telemetry lên {@code POST /api/server/status} để website render trạng thái
 * realtime cho từng node. Toàn bộ HTTP chạy async (KHÔNG chặn main thread);
 * chỉ việc đọc số liệu (players, TPS, memory) chạy trên main thread trong task.
 */
public class HeartbeatService {

    private final Plugin plugin;
    private final PluginConfig config;
    private final ApiClient api;

    private BukkitTask task;
    private final AtomicBoolean failing = new AtomicBoolean(false);

    private volatile LastResult lastResult = LastResult.none();

    public record LastResult(boolean success, Instant at, String detail) {
        static LastResult none() {
            return new LastResult(false, null, "chưa chạy");
        }
    }

    public HeartbeatService(Plugin plugin, PluginConfig config, ApiClient api) {
        this.plugin = plugin;
        this.config = config;
        this.api = api;
    }

    /** Bắt đầu heartbeat (task async). */
    public void start() {
        stop();
        if (!api.isConfigured()) {
            plugin.getLogger().warning("Heartbeat chưa bật: api.key trống.");
            return;
        }
        long interval = config.heartbeatIntervalSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, interval, interval);
        plugin.getLogger().info("Heartbeat started (mỗi " + config.heartbeatIntervalSeconds() + "s)");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Gửi một heartbeat ngay (dùng cho /bmvn sync và lúc shutdown). */
    public void tick() {
        // Đọc số liệu Bukkit trên MAIN thread (an toàn), chỉ HTTP mới async.
        Bukkit.getScheduler().runTask(plugin, () -> {
            ServerStatusPayload payload = buildPayload(config.serverState());
            if (payload == null) {
                return;
            }
            api.pushStatus(payload).whenComplete((resp, err) -> {
                if (err != null) {
                    boolean first = failing.compareAndSet(false, true);
                    if (first) {
                        plugin.getLogger().log(Level.WARNING, "Heartbeat thất bại: " + err.getMessage());
                    }
                    lastResult = new LastResult(false, Instant.now(), String.valueOf(err.getMessage()));
                } else {
                    failing.set(false);
                    lastResult = new LastResult(true, Instant.now(), "OK");
                    // Website có thể yêu cầu server chạy /bmvn exportaudit (admin bấm
                    // nút trên dashboard) — kéo lệnh chờ ngay trong luồng push có sẵn.
                    checkPendingWebCommand();
                }
            });
        });
    }

    /** Whitelist lệnh website được phép yêu cầu (không bao giờ chạy lệnh tuỳ ý). */
    static boolean isSupportedCommand(String command) {
        return "exportaudit".equals(command);
    }

    /** Poll lệnh chờ từ web; có lệnh exportaudit → chạy trên MAIN thread rồi ack. */
    private void checkPendingWebCommand() {
        api.fetchPendingCommand(config.serverId()).whenComplete((command, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.FINE, "Poll lệnh chờ từ web thất bại: " + err.getMessage());
                return;
            }
            if (!isSupportedCommand(command)) {
                return; // null hoặc lệnh không được hỗ trợ — bỏ qua an toàn
            }
            // Export đọc audit.log (AuditLogger cũng main thread) → phải chạy main.
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    ((vn.banhmivn.coresync.BanhmiVNCoreSync) plugin)
                            .performSnapshotExport("WEB_EXPORT", "web", null);
                } catch (RuntimeException ex) {
                    // Lỗi ngoài ý muốn — log để ops biết; lệnh vẫn được ack để
                    // tránh retry-loop mỗi chu kỳ heartbeat (lỗi sẽ lặp lại).
                    plugin.getLogger().log(Level.WARNING, "Web export (WEB_EXPORT) thất bại: " + ex.getMessage(), ex);
                } finally {
                    api.ackPendingCommand(config.serverId()).exceptionally(ex -> {
                        plugin.getLogger().log(Level.FINE, "Ack lệnh chờ thất bại: " + ex.getMessage());
                        return null;
                    });
                }
            });
        });
    }

    /** Payload telemetry hiện tại (đọc trên main thread — gọi từ async task là an toàn cho các getter này). */
    public ServerStatusPayload buildPayload(ServerState state) {
        int online = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();

        Double tps = null;
        try {
            double[] tpsArr = Bukkit.getTPS(); // Paper-only
            tps = Math.round(tpsArr[0] * 100.0) / 100.0;
        } catch (Throwable ignored) {
            // Spigot: không có getTPS → bỏ qua field
        }

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

        String message = config.stateMessage();
        return new ServerStatusPayload(
                state.webStatus(),
                message == null || message.isBlank() ? null : message,
                online, maxPlayers, tps, null, usedMb);
    }

    /** Gửi trạng thái offline cuối cùng khi plugin tắt (fire-and-forget). */
    public void pushFinalOffline() {
        ServerStatusPayload payload = buildPayload(ServerState.CLOSED);
        if (payload != null && api.isConfigured()) {
            api.pushStatus(payload).exceptionally(ex -> {
                plugin.getLogger().log(Level.FINE, "Final offline push thất bại", ex);
                return null;
            });
        }
    }

    public LastResult lastResult() {
        return lastResult;
    }
}
