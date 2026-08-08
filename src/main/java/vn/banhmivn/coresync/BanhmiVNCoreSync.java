package vn.banhmivn.coresync;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import vn.banhmivn.coresync.alert.AlertNotifier;
import vn.banhmivn.coresync.alert.SuspicionDetector;
import vn.banhmivn.coresync.api.ApiClient;
import vn.banhmivn.coresync.audit.AuditLogger;
import vn.banhmivn.coresync.history.RedeemHistory;
import vn.banhmivn.coresync.api.dto.CodeItem;
import vn.banhmivn.coresync.command.BmvnCommand;
import vn.banhmivn.coresync.command.NhapCodeCommand;
import vn.banhmivn.coresync.export.AuditExporter;
import vn.banhmivn.coresync.export.AuditImporter;
import vn.banhmivn.coresync.export.SnapshotAutoPush;
import vn.banhmivn.coresync.export.SnapshotCipher;
import vn.banhmivn.coresync.config.PluginConfig;
import vn.banhmivn.coresync.giftcode.GiftCodeGenerator;
import vn.banhmivn.coresync.giftcode.GiftCodeManager;
import vn.banhmivn.coresync.giftcode.UsedCodeCache;
import vn.banhmivn.coresync.heartbeat.HeartbeatService;
import vn.banhmivn.coresync.item.ItemBindingManager;
import vn.banhmivn.coresync.reward.PendingRewards;
import vn.banhmivn.coresync.reward.RewardApplier;
import vn.banhmivn.coresync.util.Chat;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * BanhmiVN-CoreSync — plugin lõi đồng bộ BanhmiVN.fun:
 * giftcode một lần, rank LuckPerms, point PlayerPoints, claim blocks
 * GriefPrevention, bind item, telemetry heartbeat realtime.
 */
public final class BanhmiVNCoreSync extends JavaPlugin implements Listener {

    private PluginConfig config;
    private ApiClient api;
    private GiftCodeGenerator generator;
    private UsedCodeCache usedCache;
    private ItemBindingManager itemBinding;
    private PendingRewards pendingRewards;
    private AuditLogger auditLogger;
    private RedeemHistory redeemHistory;
    private RewardApplier rewardApplier;
    private GiftCodeManager giftCodeManager;
    private HeartbeatService heartbeat;
    private SuspicionDetector alerts;
    private AlertNotifier alertNotifier;
    private SnapshotAutoPush autoPush;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new PluginConfig(this);

        // ── Null-check SoftDepend plugins (spec yêu cầu kiểm tra khi enable) ──
        checkSoftDepend("LuckPerms", "rank VIP/VIP+/SVIP sẽ dùng API LuckPerms");
        checkSoftDepend("PlayerPoints", "điểm sẽ cộng qua API PlayerPoints");
        checkSoftDepend("GriefPrevention", "claim blocks qua lệnh /adjustbonusclaimblocks");

        this.api = new ApiClient(
                config.apiBaseUrl(), config.apiKey(), config.apiKeyHeader(), config.apiTimeoutSeconds());
        if (!api.isConfigured()) {
            getLogger().warning("api.key rỗng — không gọi được /api/codes/redeem, /sync, /server/status. "
                    + "Đặt MC_API_KEY tương ứng trên website + api.key trong config.yml.");
        }

        this.generator = new GiftCodeGenerator();
        this.usedCache = new UsedCodeCache(this);
        this.itemBinding = new ItemBindingManager(this);
        this.pendingRewards = new PendingRewards(this);
        this.auditLogger = new AuditLogger(this);
        this.redeemHistory = new RedeemHistory(this);
        this.rewardApplier = new RewardApplier(this, config, itemBinding);
        setupAlerts();
        this.giftCodeManager = new GiftCodeManager(this, config, api, generator, usedCache,
                rewardApplier, pendingRewards, auditLogger, redeemHistory, alerts);
        this.heartbeat = new HeartbeatService(this, config, api);

        registerCommands();
        Bukkit.getPluginManager().registerEvents(this, this);
        heartbeat.start();
        pruneOldSnapshots();
        autoPush = new SnapshotAutoPush(this);
        autoPush.start();

        getLogger().info("BanhmiVN-CoreSync enabled. "
                + "Used codes cached: " + usedCache.size()
                + ", bound items: " + itemBinding.size()
                + ", pending rewards: " + pendingRewards.all().size()
                + ", audit trail: " + auditLogger.file().getName()
                + ", redeem history entries: " + redeemHistory.totalRecords());
    }

    @Override
    public void onDisable() {
        if (autoPush != null) {
            autoPush.stop();
        }
        if (heartbeat != null) {
            heartbeat.pushFinalOffline();
            heartbeat.stop();
        }
        if (alertNotifier != null) {
            alertNotifier.shutdown();
        }
        getLogger().info("BanhmiVN-CoreSync disabled.");
    }

    private void checkSoftDepend(String name, String role) {
        PluginManager pm = Bukkit.getPluginManager();
        if (pm.getPlugin(name) != null && pm.isPluginEnabled(name)) {
            getLogger().info("✓ " + name + " detected — " + role);
        } else {
            getLogger().warning("✗ " + name + " NOT found — " + role + " sẽ dùng lệnh console"
                    + " (hoặc bị từ chối nếu không có).");
        }
    }

    private void registerCommands() {
        NhapCodeCommand nhapCode = new NhapCodeCommand(config, giftCodeManager);
        BmvnCommand bmvn = new BmvnCommand(this, config, giftCodeManager, itemBinding, heartbeat);

        var nhap = getCommand("nhapcode");
        if (nhap != null) {
            nhap.setExecutor(nhapCode);
        }
        var bmvnCmd = getCommand("bmvn");
        if (bmvnCmd != null) {
            bmvnCmd.setExecutor(bmvn);
            bmvnCmd.setTabCompleter(bmvn);
        }
    }

    /** Nạp lại toàn bộ store từ disk — gọi sau khi /bmvn importaudit ghi xong. */
    public void reloadStores() {
        usedCache.load();
        pendingRewards.reload();
        itemBinding.reload();
        redeemHistory.reload();
        getLogger().info("Đã nạp lại store từ disk sau import: used-codes, pending-rewards, items, redeem-history.");
    }

    /** Nạp lại config + khởi động lại API/heartbeat (giữ cache items/used-codes). */
    public void reloadAll() {
        config.reload();
        // Bắt buộc dừng task heartbeat CŨ trước khi thay thế — nếu không mỗi lần
        // reload sẽ rò rỉ một vòng lặp heartbeat gửi telemetry trùng lặp.
        if (heartbeat != null) {
            heartbeat.stop();
        }
        api = new ApiClient(config.apiBaseUrl(), config.apiKey(), config.apiKeyHeader(),
                config.apiTimeoutSeconds());
        if (alertNotifier != null) {
            alertNotifier.shutdown();
            alertNotifier = null;
        }
        setupAlerts();
        heartbeat = new HeartbeatService(this, config, api);
        giftCodeManager = new GiftCodeManager(this, config, api, generator, usedCache,
                rewardApplier, pendingRewards, auditLogger, redeemHistory, alerts);
        heartbeat.start();
        pruneOldSnapshots();
        if (autoPush != null) {
            autoPush.stop();
        }
        autoPush = new SnapshotAutoPush(this);
        autoPush.start();
        getLogger().info("Config reloaded.");
    }

    /** Dọn snapshot cũ trong exports/ theo cấu hình retention (nếu bật). */
    private void pruneOldSnapshots() {
        if (config.exportsRetentionDays() <= 0) {
            return;
        }
        int removed = new AuditExporter(getLogger())
                .pruneExports(getDataFolder(), config.exportsRetentionDays());
        if (removed > 0) {
            getLogger().info("Đã dọn " + removed + " snapshot cũ trong exports/.");
        }
    }

    /**
     * Xuất snapshot audit + (nếu cấu hình) đẩy lên website. Dùng chung cho
     * {@code /bmvn exportaudit} và auto-push định kỳ ({@link SnapshotAutoPush}).
     *
     * @param auditEvent event ghi vào audit.log: {@code EXPORT} (lệnh) hoặc {@code AUTO_EXPORT} (tự động)
     * @param actor      tên người thực hiện (sender name) hoặc "scheduler" khi tự động
     * @param chat       sink gửi thông báo chat (nullable — auto-push không có CommandSender)
     * @return tên file snapshot vừa xuất nếu thành công (đẩy web là fire-and-forget,
     *         không ảnh hưởng kết quả này); {@code null} nếu tạo .tar.gz thất bại
     */
    public String performSnapshotExport(String auditEvent, String actor, Consumer<String> chat) {
        try {
            File dataFolder = getDataFolder();
            AuditExporter exporter = new AuditExporter(getLogger());
            AuditExporter.ExportSources sources = new AuditExporter.ExportSources(
                    new File(dataFolder, "audit.log"),
                    new File(dataFolder, "audit.log.1"),
                    new File(dataFolder, "redeem-history.yml"),
                    new File(dataFolder, "used-codes.yml"),
                    new File(dataFolder, "pending-rewards.yml"),
                    new File(dataFolder, "items.yml"));
            AuditExporter.SnapshotResult result = exporter.export(
                    dataFolder, sources, config.serverName(),
                    getDescription().getVersion(), config.exportsRetentionDays());
            String kb = String.format(Locale.ROOT, "%.1f", result.bytes() / 1024.0);
            // Ghi dấu vết việc xuất snapshot vào chính audit.log
            auditLogger.log(auditEvent, actor, "-", "",
                    result.file().getName() + " (" + result.entries() + " files, " + kb + " KB)");
            String prunedNote = result.pruned() > 0
                    ? " &7(đã dọn " + result.pruned() + " snapshot cũ)" : "";
            notify(chat, "&aĐã xuất snapshot audit: &f" + result.file().getName()
                    + " &a(" + kb + " KB, " + result.entries() + " file)" + prunedNote);
            if (chat == null) {
                // Không có CommandSender (auto-push / web-trigger) → ghi console để ops theo dõi.
                getLogger().info("Snapshot export (" + auditEvent + ", " + actor + "): đã xuất "
                        + result.file().getName() + " (" + kb + " KB, " + result.entries() + " file)");
            }
            pushSnapshotToWebsite(chat, result.file());
            notify(chat, "&7Đường dẫn: &fplugins/BanhmiVN-CoreSync/exports/" + result.file().getName());
            return result.file().getName();
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Xuất snapshot audit thất bại", ex);
            notify(chat, "&cXuất snapshot thất bại — xem log server.");
            return null;
        }
    }

    /**
     * Khôi phục snapshot từ {@code exports/<fileName>} (bước xác nhận đã thực hiện).
     * Dùng chung cho {@code /bmvn importaudit <file> confirm} và web-trigger
     * (admin bấm nút khôi phục trên dashboard — đã confirm ngay khi upload).
     *
     * @param fileName tên file .tar.gz trong thư mục {@code exports/}
     * @param actor    tên người thực hiện ({@code sender.getName()} hoặc {@code "web"})
     * @param chat     sink gửi thông báo chat (nullable — web-trigger không có CommandSender)
     * @return kết quả khôi phục
     * @throws IOException archive hỏng/không phải snapshot plugin (không ghi gì) —
     *                     caller log + báo người dùng (web-trigger dùng message này
     *                     để ack kết quả thất bại)
     */
    public AuditImporter.ImportResult performSnapshotImport(String fileName, String actor, Consumer<String> chat)
            throws IOException {
        AuditImporter importer = new AuditImporter(getLogger());
        AuditImporter.ImportResult result = importer.importSnapshot(getDataFolder(), fileName);
        // Ghi dấu vết dù khôi phục được bao nhiêu file.
        auditLogger.log("IMPORT", actor, "-", "",
                fileName + " restored=" + result.restored());
        if (result.restored().isEmpty()) {
            notify(chat, "&eSnapshot hợp lệ nhưng không chứa file trạng thái nào để khôi phục.");
            return result;
        }
        // Disk đã ghi xong → nạp lại store để bộ nhớ khớp disk.
        reloadStores();
        notify(chat, "&aĐã khôi phục &f" + result.restored().size()
                + " &afile từ &f" + fileName + "&a — đã nạp lại bộ nhớ.");
        getLogger().info("Snapshot import (" + actor + "): đã khôi phục "
                + result.restored().size() + " file từ " + fileName);
        return result;
    }

    /** Đẩy snapshot vừa xuất lên website cho staff tải (async — không chặn main). */
    private void pushSnapshotToWebsite(Consumer<String> chat, File snapshot) {
        if (!config.pushSnapshotsToWebsite()) {
            return;
        }
        if (!api.isConfigured()) {
            notify(chat, "&7Bỏ qua đẩy lên website (chưa cấu hình api.key).");
            return;
        }
        // Mã hoá at-rest: nếu key cấu hình sai → KHÔNG đẩy (fail-loud, không downgrade bản rõ).
        SnapshotCipher cipher = null;
        String keyB64 = config.exportsEncryptionKey();
        if (!keyB64.isBlank()) {
            try {
                cipher = SnapshotCipher.fromBase64(keyB64);
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Bỏ qua đẩy snapshot: " + ex.getMessage());
                notify(chat, "&cKhông đẩy snapshot lên website (key mã hoá không hợp lệ): " + ex.getMessage());
                return;
            }
        }
        final boolean encrypted = cipher != null;
        try {
            api.uploadSnapshot(snapshot, config.serverId(), cipher)
                    .whenComplete((v, err) -> Bukkit.getScheduler().runTask(this, () -> {
                        if (err != null) {
                            String detail = err instanceof IOException io
                                    ? io.getMessage()
                                    : (err.getMessage() == null ? "lỗi mạng" : err.getMessage());
                            getLogger().log(Level.WARNING,
                                    "Không đẩy được snapshot lên website: " + detail);
                            notify(chat, "&cKhông đẩy được snapshot lên website (" + detail + ").");
                        } else {
                            getLogger().info("Đã đẩy snapshot lên website: " + snapshot.getName()
                                    + (encrypted ? " (mã hoá AES-256)" : " (bản rõ)"));
                            notify(chat, "&aĐã đẩy snapshot lên website"
                                    + (encrypted ? " (mã hoá AES-256)" : " (KHÔNG mã hoá)")
                                    + " — staff tải về từ trang admin.");
                        }
                    }));
        } catch (RuntimeException ex) {
            // Phòng thủ: nếu mã hoá/Multipart lỗi sync (không nên xảy ra sau khi ApiClient
            // đã bọc failedFuture) — báo lỗi thay vì ném ra khỏi caller.
            getLogger().log(Level.WARNING, "Đẩy snapshot thất bại (sync): " + ex.getMessage());
            notify(chat, "&cĐẩy snapshot lên website thất bại: " + ex.getMessage());
        }
    }

    private static void notify(Consumer<String> chat, String message) {
        if (chat != null) {
            chat.accept(message);
        }
    }

    /**
     * Khởi tạo bộ phát hiện đáng ngờ + kênh cảnh báo theo config.
     * Tắt hoàn toàn nếu alerts.enabled=false hoặc không có kênh nào cấu hình.
     */
    private void setupAlerts() {
        if (config.alertsEnabled()) {
            alertNotifier = new AlertNotifier(this, config.serverName(),
                    config.discordWebhookUrl(), config.emailSettings());
            alerts = new SuspicionDetector(config.alertRules(), alertNotifier);
            getLogger().info("Staff alerts: " + config.alertRules().size() + " rule(s), discord="
                    + alertNotifier.discordConfigured() + ", email=" + alertNotifier.emailConfigured());
        } else {
            alertNotifier = null;
            alerts = new SuspicionDetector(java.util.List.of(), (title, message) -> { });
            getLogger().info("Staff alerts disabled (alerts.enabled=false).");
        }
    }

    // ── Trao lại reward còn nợ khi player vào server ────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        List<PendingRewards.Reward> pending = pendingRewards.takeAll(player.getName());
        if (pending.isEmpty()) {
            return;
        }
        int granted = 0;
        List<CodeItem> stillFailed = new java.util.ArrayList<>();
        for (PendingRewards.Reward reward : pending) {
            List<CodeItem> failed = rewardApplier.apply(player, reward.items());
            if (failed.isEmpty()) {
                granted++;
            } else {
                stillFailed.addAll(failed);
            }
        }
        if (granted > 0) {
            Chat.send(player, config.prefix(), "&aĐã trao lại phần thưởng còn nợ cho bạn! 🎉");
        }
        if (!stillFailed.isEmpty()) {
            pendingRewards.add(player.getName(), stillFailed);
            Chat.send(player, config.prefix(),
                    "&eMột số phần thưởng chưa trao được (vd item chưa bind) — liên hệ Admin.");
        }
    }

    // ── Getters (cho commands/tests) ────────────────────────

    public PluginConfig pluginConfig() {
        return config;
    }

    public ApiClient apiClient() {
        return api;
    }

    public UsedCodeCache usedCache() {
        return usedCache;
    }

    public ItemBindingManager itemBinding() {
        return itemBinding;
    }

    public PendingRewards pendingRewards() {
        return pendingRewards;
    }

    public AuditLogger auditLogger() {
        return auditLogger;
    }

    public RedeemHistory redeemHistory() {
        return redeemHistory;
    }

    public GiftCodeManager giftCodeManager() {
        return giftCodeManager;
    }

    public HeartbeatService heartbeat() {
        return heartbeat;
    }

    public SuspicionDetector alerts() {
        return alerts;
    }
}
