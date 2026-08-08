package vn.banhmivn.coresync.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import vn.banhmivn.coresync.ServerState;
import vn.banhmivn.coresync.alert.AlertRule;
import vn.banhmivn.coresync.alert.EmailSettings;
import vn.banhmivn.coresync.rank.RankType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Wrapper có kiểu cho config.yml (đọc lại khi /bmvn reload). */
public class PluginConfig {

    private final org.bukkit.plugin.Plugin plugin;
    private FileConfiguration cfg;

    private String apiBaseUrl;
    private String apiKey;
    private String apiKeyHeader;
    private int apiTimeoutSeconds;

    private String serverId;
    private String serverName;
    private ServerState serverState;
    private String stateMessage;
    private int heartbeatIntervalSeconds;

    private boolean rankUseApi;
    private boolean pointsUseApi;
    private boolean giveItemOnRedeem;

    private int exportsRetentionDays;
    private boolean pushSnapshotsToWebsite;
    private int exportsAutoPushIntervalHours;
    private String exportsEncryptionKey;
    private String exportsCommandHmacKey;

    private boolean alertsEnabled;
    private String discordWebhookUrl;
    private EmailSettings emailSettings;
    private List<AlertRule> alertRules;

    private String prefix;
    private String msgInvalidCode;
    private String msgAlreadyUsed;
    private String msgRedeemSuccess;
    private String msgNotOnline;
    private String msgRejectedCode;

    // ── Message bổ sung (đưa vào config để chủ server tự chỉnh màu/text) ──
    private String msgCooldown;            // nhập liên tục quá nhanh
    private String msgApiNotConfigured;    // hệ thống chưa sẵn sàng nhận mã
    private String msgErrorOccurred;       // lỗi chung khi xác nhận mã
    private String msgNetworkError;        // không kết nối được website
    private String msgPendingRewards;      // một số phần thưởng chưa trao (kèm {items})
    private String msgRewardsReceived;     // "Bạn nhận được: {items}"
    private String msgPendingRewardsJoin;  // khi player vào lại còn nợ
    private String msgRewardsPaidBack;     // đã trao lại phần thưởng còn nợ
    private String msgSyncNotConfigured;   // admin: chưa cấu hình api.key
    private String msgSyncFail;            // admin: không đăng ký được code ({detail})
    private String msgSyncSuccess;         // admin: đã tạo code ({code},{qty},{product})
    private String msgConsoleOnly;         // /nhapcode từ console
    private String msgNoPermission;        // thiếu quyền
    private String msgUsage;               // sai cú pháp ({label})

    public PluginConfig(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        this.cfg = plugin.getConfig();

        apiBaseUrl = cfg.getString("api.base-url", "https://banhmivn.fun");
        apiKey = cfg.getString("api.key", "");
        apiKeyHeader = cfg.getString("api.key-header", "X-API-Key");
        apiTimeoutSeconds = cfg.getInt("api.timeout-seconds", 10);

        serverId = cfg.getString("server.id", "main");
        serverName = cfg.getString("server.name", "BanhmiVN");
        serverState = ServerState.fromConfig(cfg.getString("server.state", "ONLINE"));
        stateMessage = cfg.getString("server.state-message", "");
        heartbeatIntervalSeconds = Math.max(5, cfg.getInt("server.heartbeat-interval-seconds", 15));

        rankUseApi = cfg.getBoolean("ranks.use-api", true);
        pointsUseApi = cfg.getBoolean("points.use-api", true);
        giveItemOnRedeem = cfg.getBoolean("items.give-on-redeem", true);

        exportsRetentionDays = Math.max(0, cfg.getInt("exports.retention-days", 30));
        pushSnapshotsToWebsite = cfg.getBoolean("exports.push-to-website", true);
        exportsAutoPushIntervalHours = Math.max(0, cfg.getInt("exports.auto-push-interval-hours", 6));
        exportsEncryptionKey = cfg.getString("exports.encryption-key", "").trim();
        exportsCommandHmacKey = cfg.getString("exports.command-hmac-key", "").trim();
        if (pushSnapshotsToWebsite && exportsEncryptionKey.isEmpty()) {
            plugin.getLogger().warning(
                    "exports.encryption-key chưa cấu hình — snapshot đẩy lên website sẽ KHÔNG được mã hoá. "
                            + "Đặt cùng giá trị base64 với SNAPSHOT_ENCRYPTION_KEY trên website để mã hoá at-rest.");
        }
        if (pushSnapshotsToWebsite && exportsCommandHmacKey.isEmpty() && exportsEncryptionKey.isEmpty()) {
            plugin.getLogger().warning(
                    "KHÔNG có secret nào cấu hình (exports.command-hmac-key hoặc exports.encryption-key) — "
                            + "kênh lệnh từ web (exportaudit/importaudit) KHÔNG có chữ ký HMAC. Kẻ lộ "
                            + "MC_API_KEY có thể giả mạo lệnh; cấu hình secret để khoá nguồn.");
        }

        alertsEnabled = cfg.getBoolean("alerts.enabled", true);
        discordWebhookUrl = cfg.getString("alerts.discord-webhook-url", "").trim();
        emailSettings = parseEmailSettings();
        alertRules = parseAlertRules();

        prefix = cfg.getString("messages.prefix", "&8[&bBanhmiVN&8] ");
        msgInvalidCode = cfg.getString("messages.invalid-code", "&cMã không hợp lệ hoặc không tồn tại.");
        msgAlreadyUsed = cfg.getString("messages.already-used", "&cMã này đã được sử dụng trước đó.");
        msgRedeemSuccess = cfg.getString("messages.redeem-success", "&a🎉 Nhập code thành công! Đã trao phần thưởng cho bạn.");
        msgNotOnline = cfg.getString("messages.not-online", "&cVui lòng đăng nhập lại để nhận phần thưởng.");
        msgRejectedCode = cfg.getString("messages.rejected-code",
                "&cMã này đã bị thu hồi/vô hiệu hoá. Liên hệ Admin nếu cần hỗ trợ.");

        msgCooldown = cfg.getString("messages.cooldown",
                "&cHãy chờ một chút rồi thử lại.");
        msgApiNotConfigured = cfg.getString("messages.api-not-configured",
                "&cHệ thống BanhmiVN chưa sẵn sàng nhận mã. Vui lòng liên hệ Admin.");
        msgErrorOccurred = cfg.getString("messages.error-occurred",
                "&cCó lỗi xảy ra khi xác nhận mã. Thử lại sau ít phút.");
        msgNetworkError = cfg.getString("messages.network-error",
                "&cKhông kết nối được server BanhmiVN. Thử lại sau.");
        msgPendingRewards = cfg.getString("messages.pending-rewards",
                "&eMột số phần thưởng chưa trao được (&f{items}&e) — sẽ tự động nhận khi bạn vào lại server.");
        msgRewardsReceived = cfg.getString("messages.rewards-received",
                "&aBạn nhận được: &f{items}");
        msgPendingRewardsJoin = cfg.getString("messages.pending-rewards-join",
                "&eMột số phần thưởng chưa trao được (vd item chưa bind) — liên hệ Admin.");
        msgRewardsPaidBack = cfg.getString("messages.rewards-paid-back",
                "&aĐã trao lại phần thưởng còn nợ cho bạn! 🎉");
        msgSyncNotConfigured = cfg.getString("messages.sync-not-configured",
                "&cChưa cấu hình api.key — không thể sync code.");
        msgSyncFail = cfg.getString("messages.sync-fail",
                "&cKhông đăng ký được code lên website: {detail}");
        msgSyncSuccess = cfg.getString("messages.sync-success",
                "&aĐã tạo giftcode &f{code}&a ({qty}x {product}) — đã đồng bộ lên website.");
        msgConsoleOnly = cfg.getString("messages.console-only",
                "&cChỉ người chơi trong game mới nhập được giftcode.");
        msgNoPermission = cfg.getString("messages.no-permission",
                "&cBạn không có quyền sử dụng lệnh này.");
        msgUsage = cfg.getString("messages.usage",
                "&cSử dụng: /{label} <code>");
    }

    private EmailSettings parseEmailSettings() {
        ConfigurationSection sec = cfg.getConfigurationSection("alerts.email");
        if (sec == null) {
            return EmailSettings.disabled();
        }
        List<String> to = sec.getStringList("to");
        boolean enabled = sec.getBoolean("enabled", false)
                && !sec.getString("smtp-host", "").isBlank()
                && !sec.getString("from", "").isBlank()
                && !to.isEmpty();
        if (enabled && sec.getString("smtp-username", "").isBlank()) {
            plugin.getLogger().warning("alerts.email bật nhưng thiếu smtp-username — email cảnh báo sẽ thất bại.");
        }
        return new EmailSettings(
                enabled,
                sec.getString("smtp-host", ""),
                Math.max(1, sec.getInt("smtp-port", 587)),
                sec.getString("smtp-username", ""),
                sec.getString("smtp-password", ""),
                sec.getBoolean("smtp-ssl", false),
                sec.getString("from", ""),
                List.copyOf(to));
    }

    /** Đọc danh sách quy tắc cảnh báo; bỏ qua rule thiếu/invalid (kèm log). */
    private List<AlertRule> parseAlertRules() {
        List<AlertRule> rules = new ArrayList<>();
        ConfigurationSection sec = cfg.getConfigurationSection("alerts.rules");
        if (sec == null) {
            return rules;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection rule = sec.getConfigurationSection(key);
            if (rule == null) {
                continue;
            }
            try {
                rules.add(new AlertRule(
                        key,
                        rule.getString("event", ""),
                        rule.getLong("window-seconds", 60),
                        rule.getInt("threshold", 5),
                        rule.getLong("cooldown-seconds", 300),
                        rule.getBoolean("enabled", true)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Bỏ qua rule cảnh báo không hợp lệ '" + key + "': " + ex.getMessage());
            }
        }
        return List.copyOf(rules);
    }

    /** Nhóm LuckPerms theo rank (giá trị từ config, đã hợp lệ). */
    public Map<RankType, String> rankGroups() {
        Map<RankType, String> map = new HashMap<>();
        String vip = cfg.getString("ranks.groups.vip", "vip");
        String vipPlus = cfg.getString("ranks.groups.vip_plus", "vip_plus");
        String svip = cfg.getString("ranks.groups.svip", "svip");
        map.put(RankType.VIP, sanitizeGroup(vip, "vip"));
        map.put(RankType.VIP_PLUS, sanitizeGroup(vipPlus, "vip_plus"));
        map.put(RankType.SVIP, sanitizeGroup(svip, "svip"));
        return map;
    }

    /** Chỉ cho phép ký tự an toàn cho tên nhóm LuckPerms (chống command injection). */
    private String sanitizeGroup(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9_+\\-]", "");
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    public String apiBaseUrl() {
        return apiBaseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String apiKeyHeader() {
        return apiKeyHeader;
    }

    public int apiTimeoutSeconds() {
        return apiTimeoutSeconds;
    }

    public String serverId() {
        return serverId;
    }

    public String serverName() {
        return serverName;
    }

    public ServerState serverState() {
        return serverState;
    }

    public String stateMessage() {
        return stateMessage;
    }

    public int heartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    /** Số ngày giữ snapshot trong exports/ (0 = không dọn dẹp). */
    public int exportsRetentionDays() {
        return exportsRetentionDays;
    }

    /** Đẩy snapshot lên website (POST /api/export) sau khi xuất. */
    public boolean pushSnapshotsToWebsite() {
        return pushSnapshotsToWebsite;
    }

    /**
     * Key mã hoá AES-256 (base64) cho snapshot đẩy lên website.
     * Rỗng = gửi bản rõ (cảnh báo khi bật push-to-website).
     */
    public String exportsEncryptionKey() {
        return exportsEncryptionKey;
    }

    /**
     * Khoá HMAC ký lệnh từ web (exports.command-hmac-key), mặc định tái dùng
     * exports.encryption-key (32 byte đã chia sẻ với website) để khỏi cấu hình
     * thêm. Rỗng → kênh lệnh không ký (legacy, có cảnh báo lúc reload).
     */
    public String commandHmacKey() {
        return exportsCommandHmacKey.isEmpty() ? exportsEncryptionKey : exportsCommandHmacKey;
    }

    /**
     * Chu kỳ auto-push snapshot (giờ); 0 = tắt.
     * Chạy định kỳ xuất + đẩy snapshot lên website kể cả khi không ai dùng /bmvn exportaudit.
     */
    public int exportsAutoPushIntervalHours() {
        return exportsAutoPushIntervalHours;
    }

    public boolean alertsEnabled() {
        return alertsEnabled;
    }

    public String discordWebhookUrl() {
        return discordWebhookUrl;
    }

    public EmailSettings emailSettings() {
        return emailSettings;
    }

    public List<AlertRule> alertRules() {
        return alertRules;
    }

    public boolean rankUseApi() {
        return rankUseApi;
    }

    public boolean pointsUseApi() {
        return pointsUseApi;
    }

    public boolean giveItemOnRedeem() {
        return giveItemOnRedeem;
    }

    public String prefix() {
        return prefix;
    }

    public String msgInvalidCode() {
        return msgInvalidCode;
    }

    public String msgAlreadyUsed() {
        return msgAlreadyUsed;
    }

    public String msgRedeemSuccess() {
        return msgRedeemSuccess;
    }

    public String msgNotOnline() {
        return msgNotOnline;
    }

    /** Thông báo khi mã bị thu hồi (đơn hàng bị admin từ chối) — 410 từ website. */
    public String msgRejectedCode() {
        return msgRejectedCode;
    }

    public String msgCooldown() {
        return msgCooldown;
    }

    public String msgApiNotConfigured() {
        return msgApiNotConfigured;
    }

    public String msgErrorOccurred() {
        return msgErrorOccurred;
    }

    public String msgNetworkError() {
        return msgNetworkError;
    }

    public String msgPendingRewards() {
        return msgPendingRewards;
    }

    public String msgRewardsReceived() {
        return msgRewardsReceived;
    }

    public String msgPendingRewardsJoin() {
        return msgPendingRewardsJoin;
    }

    public String msgRewardsPaidBack() {
        return msgRewardsPaidBack;
    }

    public String msgSyncNotConfigured() {
        return msgSyncNotConfigured;
    }

    public String msgSyncFail() {
        return msgSyncFail;
    }

    public String msgSyncSuccess() {
        return msgSyncSuccess;
    }

    public String msgConsoleOnly() {
        return msgConsoleOnly;
    }

    public String msgNoPermission() {
        return msgNoPermission;
    }

    public String msgUsage() {
        return msgUsage;
    }
}
