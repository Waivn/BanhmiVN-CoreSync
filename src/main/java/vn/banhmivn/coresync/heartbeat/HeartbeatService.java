package vn.banhmivn.coresync.heartbeat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import vn.banhmivn.coresync.ServerState;
import vn.banhmivn.coresync.api.ApiClient;
import vn.banhmivn.coresync.api.dto.PendingCommandResponse;
import vn.banhmivn.coresync.api.dto.ServerStatusPayload;
import vn.banhmivn.coresync.config.PluginConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Base64;
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
        return "exportaudit".equals(command) || "importaudit".equals(command);
    }

    /** Poll lệnh chờ từ web; có lệnh hợp lệ → chạy trên MAIN thread rồi ack. */
    private void checkPendingWebCommand() {
        api.fetchPendingCommand(config.serverId()).whenComplete((pending, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.FINE, "Poll lệnh chờ từ web thất bại: " + err.getMessage());
                return;
            }
            String command = pending == null ? null : pending.getCommand();
            if (!isSupportedCommand(command)) {
                return; // null hoặc lệnh không được hỗ trợ — bỏ qua an toàn
            }
            // Xác thực chữ ký HMAC trước khi làm bất cứ điều gì: nếu plugin đã cấu
            // hình secret mà lệnh thiếu/sai chữ ký → từ chối (fail-closed), kẻ lộ
            // MC_API_KEY không giả mạo được lệnh.
            String hmacB64 = config.commandHmacKey();
            boolean signed = true;
            String rejectReason = null;
            if (!hmacB64.isBlank()) {
                try {
                    byte[] hmacKey = vn.banhmivn.coresync.export.CommandHmac.keyFromBase64(hmacB64);
                    if (pending.getSig() == null || pending.getSig().isBlank()) {
                        // Website chưa gửi chữ ký (chưa cấu hình secret) — khác hẳn chữ ký sai.
                        signed = false;
                        rejectReason = "Website chưa gửi chữ ký HMAC (chưa cấu hình secret trên website?)";
                    } else if (!vn.banhmivn.coresync.export.CommandHmac.verify(
                            hmacKey, pending.getServer(), command,
                            pending.getCreatedAt(), pending.getFileB64(),
                            pending.getRequestedBy(), pending.getSig())) {
                        signed = false;
                        rejectReason = "Chữ ký HMAC không khớp (secret giữa plugin và website khác nhau?)";
                    }
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Khoá HMAC lệnh web không hợp lệ: " + ex.getMessage());
                    signed = false;
                    rejectReason = "Khoá HMAC cấu hình không hợp lệ trên plugin";
                }
            }
            if (!signed) {
                plugin.getLogger().warning("Từ chối lệnh web '" + command + "': " + rejectReason);
                // Ack failed để website hiện ✗ + không retry-loop lệnh giả.
                api.ackPendingCommand(config.serverId(), "failed", rejectReason,
                        pending.getCreatedAt()).exceptionally(ex -> {
                    plugin.getLogger().log(Level.FINE, "Ack từ chối lệnh thất bại: " + ex.getMessage());
                    return null;
                });
                return;
            }
            // Actor ghi vào audit.log = người yêu cầu trên dashboard (email admin),
            // fallback "web" nếu website cũ chưa gửi.
            final String webActor = pending.getRequestedBy() == null || pending.getRequestedBy().isBlank()
                    ? "web" : pending.getRequestedBy();
            // "importaudit": giải mã base64 + ghi file là thuần I/O — làm ngay trên
            // thread async này (không đụng Bukkit state) để tránh lag main thread với
            // snapshot lớn (tối đa 50MB). Chỉ import (đụng store) mới chạy main.
            String importFileName = null;
            String importPrepError = null;
            if ("importaudit".equals(command)) {
                try {
                    importFileName = writeWebImportFile(pending);
                } catch (RuntimeException ex) {
                    importPrepError = ex.getMessage();
                    plugin.getLogger().log(Level.WARNING,
                            "Web lệnh 'importaudit' thất bại (chuẩn bị file): " + importPrepError);
                    // fileName giữ null → import được bỏ qua, lệnh vẫn được ack dưới đây.
                }
            }
            final String fileName = importFileName;
            final String prepError = importPrepError;
            // Token chống ack cũ đè lệnh mới: website chỉ ghi kết quả khi created_at khớp.
            final String token = pending.getCreatedAt();
            // Export/import đọc audit.log + store (AuditLogger cũng main thread) → phải chạy main.
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Ack kèm KẾT QUẢ (success/failed + detail) để dashboard hiện ✓/✗.
                String ackResult = "success";
                String ackDetail = "";
                try {
                    if ("importaudit".equals(command)) {
                        if (fileName != null) {
                            vn.banhmivn.coresync.export.AuditImporter.ImportResult importResult =
                                    ((vn.banhmivn.coresync.BanhmiVNCoreSync) plugin)
                                            .performSnapshotImport(fileName, webActor, null);
                            if (importResult.restored().isEmpty()) {
                                // Hợp lệ nhưng không khôi phục được file nào → báo failed rõ ràng.
                                ackResult = "failed";
                                ackDetail = "Snapshot hợp lệ nhưng không chứa file trạng thái nào";
                            } else {
                                ackDetail = fileName + " (" + importResult.restored().size() + " file)";
                            }
                        } else {
                            ackResult = "failed";
                            ackDetail = prepError != null ? prepError : "Không nhận được snapshot từ web";
                        }
                    } else {
                        String exported = ((vn.banhmivn.coresync.BanhmiVNCoreSync) plugin)
                                .performSnapshotExport("WEB_EXPORT", webActor, null);
                        if (exported == null) {
                            ackResult = "failed";
                            ackDetail = "Xuất snapshot thất bại — xem log server";
                        } else {
                            ackDetail = exported;
                        }
                    }
                } catch (IOException ex) {
                    // Import thất bại (archive hỏng/không phải snapshot plugin) — lấy message làm detail.
                    ackResult = "failed";
                    ackDetail = String.valueOf(ex.getMessage());
                } catch (RuntimeException ex) {
                    // Lỗi ngoài ý muốn — log để ops biết; lệnh vẫn được ack để
                    // tránh retry-loop mỗi chu kỳ heartbeat (lỗi sẽ lặp lại).
                    ackResult = "failed";
                    ackDetail = String.valueOf(ex.getMessage());
                    plugin.getLogger().log(Level.WARNING,
                            "Web lệnh '" + command + "' thất bại: " + ex.getMessage(), ex);
                } finally {
                    api.ackPendingCommand(config.serverId(), ackResult, ackDetail, token).exceptionally(ex -> {
                        plugin.getLogger().log(Level.FINE, "Ack lệnh chờ thất bại: " + ex.getMessage());
                        return null;
                    });
                }
            });
        });
    }

    /**
     * Giải mã base64 → ghi {@code exports/web-import-<ts>.tar.gz} (tên phẳng).
     * Thuần I/O — gọi từ thread async, không đụng Bukkit state. Lỗi bọc
     * RuntimeException để caller log + vẫn ack (không retry-loop).
     */
    private String writeWebImportFile(PendingCommandResponse pending) {
        try {
            if (pending.getFileB64() == null || pending.getFileB64().isBlank()) {
                throw new IOException("Thiếu dữ liệu snapshot trong lệnh importaudit");
            }
            // decode chuẩn (padding đúng); IllegalArgumentException là RuntimeException → caller bắt.
            byte[] bytes = Base64.getDecoder().decode(pending.getFileB64());
            File exports = new File(plugin.getDataFolder(), "exports");
            if (!exports.isDirectory() && !exports.mkdirs()) {
                throw new IOException("Không tạo được thư mục exports/");
            }
            File snapshot = new File(exports, "web-import-" + System.currentTimeMillis() + ".tar.gz");
            Files.write(snapshot.toPath(), bytes);
            return snapshot.getName();
        } catch (IOException ex) {
            throw new RuntimeException("Không ghi được snapshot import: " + ex.getMessage(), ex);
        }
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
        // Website giới hạn ServerStatusUpdateRequest.message ở max_length=200 —
        // cắt sớm ở đây để heartbeat không bị từ chối 422 khi admin đặt
        // state-message quá dài trong config.yml.
        if (message != null && message.length() > 200) {
            message = message.substring(0, 200);
        }
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
