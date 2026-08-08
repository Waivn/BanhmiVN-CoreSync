package vn.banhmivn.coresync.giftcode;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import vn.banhmivn.coresync.alert.SuspicionDetector;
import vn.banhmivn.coresync.audit.AuditLogger;
import vn.banhmivn.coresync.api.ApiClient;
import vn.banhmivn.coresync.api.ApiException;
import vn.banhmivn.coresync.api.dto.CodeItem;
import vn.banhmivn.coresync.api.dto.CodeRedeemResponse;
import vn.banhmivn.coresync.api.dto.CodeSyncRequest;
import vn.banhmivn.coresync.api.dto.CodeSyncResponse;
import vn.banhmivn.coresync.config.PluginConfig;
import vn.banhmivn.coresync.history.RedeemHistory;
import vn.banhmivn.coresync.reward.PendingRewards;
import vn.banhmivn.coresync.reward.RewardApplier;
import vn.banhmivn.coresync.util.Chat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Quản lý vòng đời Giftcode:
 *
 * <ul>
 *   <li><b>Redeem</b> — async POST {@code /api/codes/redeem}; thành công thì trao
 *       reward trên main thread; cache USED cục bộ; các mã lỗi 404/409/410 xử lý riêng.</li>
 *   <li><b>Generate + Sync</b> — plugin tự sinh code BMVN-XXXX-XXXX-XXXX và đăng ký
 *       qua {@code POST /api/codes/sync} để website lưu vào DB.</li>
 * </ul>
 */
public class GiftCodeManager {

    private final Plugin plugin;
    private final PluginConfig config;
    private final ApiClient api;
    private final GiftCodeGenerator generator;
    private final UsedCodeCache usedCache;
    private final RewardApplier rewardApplier;
    private final PendingRewards pendingRewards;
    private final AuditLogger audit;
    private final RedeemHistory redeemHistory;
    /** Phát hiện brute-force / hành vi đáng ngờ từ stream event redeem. */
    private final SuspicionDetector alerts;

    /** Cooldown nhẹ chống spam /nhapcode (3s/player) — mọi truy cập trên main thread. */
    private static final long REDEEM_COOLDOWN_MS = 3000;
    private final java.util.Map<java.util.UUID, Long> lastRedeem = new java.util.concurrent.ConcurrentHashMap<>();

    public GiftCodeManager(Plugin plugin, PluginConfig config, ApiClient api,
                           GiftCodeGenerator generator, UsedCodeCache usedCache,
                           RewardApplier rewardApplier, PendingRewards pendingRewards,
                           AuditLogger audit, RedeemHistory redeemHistory,
                           SuspicionDetector alerts) {
        this.plugin = plugin;
        this.config = config;
        this.api = api;
        this.generator = generator;
        this.usedCache = usedCache;
        this.rewardApplier = rewardApplier;
        this.pendingRewards = pendingRewards;
        this.audit = audit;
        this.redeemHistory = redeemHistory;
        this.alerts = alerts;
    }

    // ── Redeem ──────────────────────────────────────────────

    /**
     * Người chơi nhập code. Async hoàn toàn; toàn bộ UI feedback trên main thread.
     */
    public void redeem(Player player, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();

        long now = System.currentTimeMillis();
        Long prev = lastRedeem.put(player.getUniqueId(), now);
        if (prev != null && now - prev < REDEEM_COOLDOWN_MS) {
            Chat.send(player, config.prefix(), config.msgCooldown());
            return;
        }

        if (!GiftCodeGenerator.isValidFormat(code)) {
            audit.logRedeemInvalid(player.getName(), code, "bad-format");
            alerts.observe("REDEEM_INVALID", player.getName(), "bad-format");
            Chat.send(player, config.prefix(), config.msgInvalidCode());
            return;
        }
        if (usedCache.isUsed(code)) {
            audit.logRedeemAlreadyUsed(player.getName(), code);
            alerts.observe("REDEEM_USED", player.getName(), "already-used-local");
            Chat.send(player, config.prefix(), config.msgAlreadyUsed());
            return;
        }
        if (!api.isConfigured()) {
            audit.logRedeemFail(player.getName(), code, "api-not-configured");
            Chat.send(player, config.prefix(), config.msgApiNotConfigured());
            return;
        }

        api.redeemCode(code, player.getName())
                .whenComplete((response, err) -> onRedeemComplete(player, code, response, err));
    }

    private void onRedeemComplete(Player player, String code, CodeRedeemResponse response, Throwable err) {
        // Chuyển mọi xử lý về main thread (an toàn Bukkit API).
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                // Player thoát giữa chừng → vẫn đánh dấu mã (web đã atomic claim),
                // reward sẽ được trao lại khi player vào (pending).
                if (err == null && response != null) {
                    usedCache.markUsed(code, player.getName());
                    pendingRewards.add(player.getName(), response.getItems());
                    // Player thoát giữa chừng: web đã atomic claim → vẫn ghi audit + history.
                    audit.logRedeemOk(player.getName(), code, response.getItems(), "offline->pending");
                    redeemHistory.add(player.getName(), code, response.getItems());
                }
                plugin.getLogger().info("Player " + player.getName() + " offline khi redeem " + code);
                return;
            }

            if (err != null) {
                handleRedeemError(player, code, err);
                return;
            }
            if (response == null) {
                // 2xx nhưng body rỗng — coi như lỗi, không trao đồ, code vẫn unused.
                audit.logRedeemFail(player.getName(), code, "empty-200-body");
                plugin.getLogger().warning("Redeem trả về 2xx nhưng body rỗng: " + code);
                Chat.send(player, config.prefix(), config.msgErrorOccurred());
                return;
            }

            // Website đã atomic đánh dấu USED → trao đồ.
            usedCache.markUsed(code, player.getName());
            List<CodeItem> failed = rewardApplier.apply(player, response.getItems());
            if (!failed.isEmpty()) {
                // Trao lại khi player vào server lần sau.
                pendingRewards.add(player.getName(), failed);
                Chat.send(player, config.prefix(),
                        config.msgPendingRewards().replace("{items}", rewardsToString(failed)));
            }
            Chat.send(player, config.prefix(), config.msgRedeemSuccess());
            if (response.getItems() != null && !response.getItems().isEmpty()) {
                Chat.send(player, config.prefix(),
                        config.msgRewardsReceived().replace("{items}", rewardsToString(response.getItems())));
            }
            audit.logRedeemOk(player.getName(), code, response.getItems(),
                    "order=" + (response.getOrderCode() == null ? "-" : response.getOrderCode()));
            redeemHistory.add(player.getName(), code, response.getItems());
            plugin.getLogger().info(
                    "Giftcode redeemed: " + code + " by " + player.getName()
                            + " items=" + response.getItems());
        });
    }

    private void handleRedeemError(Player player, String code, Throwable err) {
        if (err instanceof ApiException apiErr) {
            if (apiErr.getStatusCode() == 410) {
                // Đơn hàng bị ADMIN TỪ CHỐI — mã vô hiệu vĩnh viễn, KHÁC "đã dùng".
                // Cache lại để không thử lặp; message lấy từ config (messages.rejected-code).
                // Ghi REDEEM_FAIL (không phải REDEEM_INVALID) để không tính vào cảnh báo
                // brute-force — mã bị từ chối không phải hành vi thử sai liên tục.
                audit.logRedeemFail(player.getName(), code,
                        "rejected-order: " + apiErr.getDetail());
                usedCache.markUsed(code, player.getName());
                Chat.send(player, config.prefix(), config.msgRejectedCode());
                return;
            }
            if (apiErr.isAlreadyUsed()) {
                // Website xác nhận mã đã dùng (hoặc đơn bị từ chối) → cache lại.
                audit.logRedeemAlreadyUsed(player.getName(), code);
                alerts.observe("REDEEM_USED", player.getName(), "already-used-web");
                usedCache.markUsed(code, player.getName());
                Chat.send(player, config.prefix(), config.msgAlreadyUsed());
                return;
            }
            if (apiErr.isNotFound()) {
                audit.logRedeemInvalid(player.getName(), code, "not-found");
                alerts.observe("REDEEM_INVALID", player.getName(), "not-found");
                Chat.send(player, config.prefix(), config.msgInvalidCode());
                return;
            }
            audit.logRedeemFail(player.getName(), code, "http-" + apiErr.getStatusCode());
            plugin.getLogger().log(Level.WARNING,
                    "Redeem thất bại code=" + code + " player=" + player.getName(), apiErr);
            Chat.send(player, config.prefix(), config.msgErrorOccurred());
            return;
        }
        audit.logRedeemFail(player.getName(), code, "network:" + (err.getMessage() == null ? "?" : err.getMessage()));
        plugin.getLogger().log(Level.WARNING,
                "Network lỗi khi redeem code=" + code + " player=" + player.getName(), err);
        Chat.send(player, config.prefix(), config.msgNetworkError());
    }

    // ── Generate + Sync ─────────────────────────────────────

    /**
     * Sinh code mới + đăng ký lên website. Callback chạy trên main thread.
     */
    public void generateAndSync(org.bukkit.command.CommandSender sender,
                                String productType, String productName, int qty) {
        if (!api.isConfigured()) {
            Chat.send(sender, config.prefix(), config.msgSyncNotConfigured());
            return;
        }
        String code = generator.generate();
        CodeItem item = new CodeItem(productType, productName, qty);
        CodeSyncRequest request = new CodeSyncRequest(
                code, sender instanceof Player p ? p.getName() : null,
                List.of(item));
        audit.logGenerate(sender.getName(), code, List.of(item));

        api.syncCode(request).whenComplete((resp, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        audit.logSyncFail(code, err instanceof ApiException a
                                ? "http-" + a.getStatusCode() + " " + a.getDetail()
                                : "network:" + (err.getMessage() == null ? "?" : err.getMessage()));
                        plugin.getLogger().log(Level.WARNING, "Sync code thất bại: " + code, err);
                        Chat.send(sender, config.prefix(), config.msgSyncFail().replace(
                                "{detail}",
                                err instanceof ApiException ae ? ae.getDetail() : "lỗi mạng"));
                        return;
                    }
                    audit.logSyncOk(code, resp == null ? null : resp.getOrderId());
                    Chat.send(sender, config.prefix(), config.msgSyncSuccess()
                            .replace("{code}", code)
                            .replace("{qty}", String.valueOf(qty))
                            .replace("{product}", productName));
                }));
    }

    /**
     * Liệt kê phần thưởng thân thiện cho player — chỉ dùng product_name (đã có
     * emoji + tiếng Việt), KHÔNG lộ product_type kỹ thuật (rank/point/land...):
     * "👑 Rank VIP+ ×1, 💎 Đổi Point Server ×500".
     */
    private static String rewardsToString(List<CodeItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CodeItem it : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(it.getProductName()).append(" ×").append(it.getQty());
        }
        return sb.toString();
    }

    public GiftCodeGenerator generator() {
        return generator;
    }
}
