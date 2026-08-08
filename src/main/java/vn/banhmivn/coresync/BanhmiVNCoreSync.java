package vn.banhmivn.coresync;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import vn.banhmivn.coresync.api.ApiClient;
import vn.banhmivn.coresync.audit.AuditLogger;
import vn.banhmivn.coresync.history.RedeemHistory;
import vn.banhmivn.coresync.api.dto.CodeItem;
import vn.banhmivn.coresync.command.BmvnCommand;
import vn.banhmivn.coresync.command.NhapCodeCommand;
import vn.banhmivn.coresync.config.PluginConfig;
import vn.banhmivn.coresync.giftcode.GiftCodeGenerator;
import vn.banhmivn.coresync.giftcode.GiftCodeManager;
import vn.banhmivn.coresync.giftcode.UsedCodeCache;
import vn.banhmivn.coresync.heartbeat.HeartbeatService;
import vn.banhmivn.coresync.item.ItemBindingManager;
import vn.banhmivn.coresync.reward.PendingRewards;
import vn.banhmivn.coresync.reward.RewardApplier;
import vn.banhmivn.coresync.util.Chat;

import java.util.List;

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
        this.giftCodeManager = new GiftCodeManager(this, config, api, generator, usedCache,
                rewardApplier, pendingRewards, auditLogger, redeemHistory);
        this.heartbeat = new HeartbeatService(this, config, api);

        registerCommands();
        Bukkit.getPluginManager().registerEvents(this, this);
        heartbeat.start();

        getLogger().info("BanhmiVN-CoreSync enabled. "
                + "Used codes cached: " + usedCache.size()
                + ", bound items: " + itemBinding.size()
                + ", pending rewards: " + pendingRewards.all().size()
                + ", audit trail: " + auditLogger.file().getName()
                + ", redeem history entries: " + redeemHistory.totalRecords());
    }

    @Override
    public void onDisable() {
        if (heartbeat != null) {
            heartbeat.pushFinalOffline();
            heartbeat.stop();
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
        heartbeat = new HeartbeatService(this, config, api);
        giftCodeManager = new GiftCodeManager(this, config, api, generator, usedCache,
                rewardApplier, pendingRewards, auditLogger, redeemHistory);
        heartbeat.start();
        getLogger().info("Config reloaded.");
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
}
