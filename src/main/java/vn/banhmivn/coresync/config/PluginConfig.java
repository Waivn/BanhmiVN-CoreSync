package vn.banhmivn.coresync.config;

import org.bukkit.configuration.file.FileConfiguration;
import vn.banhmivn.coresync.ServerState;
import vn.banhmivn.coresync.rank.RankType;

import java.util.HashMap;
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

    private String prefix;
    private String msgInvalidCode;
    private String msgAlreadyUsed;
    private String msgRedeemSuccess;
    private String msgNotOnline;

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

        prefix = cfg.getString("messages.prefix", "&8[&bBanhmiVN&8] ");
        msgInvalidCode = cfg.getString("messages.invalid-code", "&cMã không hợp lệ hoặc không tồn tại.");
        msgAlreadyUsed = cfg.getString("messages.already-used", "&cMã này đã được sử dụng trước đó.");
        msgRedeemSuccess = cfg.getString("messages.redeem-success", "&a🎉 Nhập code thành công! Đã trao phần thưởng cho bạn.");
        msgNotOnline = cfg.getString("messages.not-online", "&cVui lòng đăng nhập lại để nhận phần thưởng.");
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
}
