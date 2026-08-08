package vn.banhmivn.coresync.giftcode;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import vn.banhmivn.coresync.api.ApiClient;
import vn.banhmivn.coresync.api.ApiException;
import vn.banhmivn.coresync.api.dto.CodeItem;
import vn.banhmivn.coresync.api.dto.CodeRedeemResponse;
import vn.banhmivn.coresync.api.dto.CodeSyncRequest;
import vn.banhmivn.coresync.api.dto.CodeSyncResponse;
import vn.banhmivn.coresync.config.PluginConfig;
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

    /** Cooldown nhẹ chống spam /nhapcode (3s/player) — mọi truy cập trên main thread. */
    private static final long REDEEM_COOLDOWN_MS = 3000;
    private final java.util.Map<java.util.UUID, Long> lastRedeem = new java.util.concurrent.ConcurrentHashMap<>();

    public GiftCodeManager(Plugin plugin, PluginConfig config, ApiClient api,
                           GiftCodeGenerator generator, UsedCodeCache usedCache,
                           RewardApplier rewardApplier, PendingRewards pendingRewards) {
        this.plugin = plugin;
        this.config = config;
        this.api = api;
        this.generator = generator;
        this.usedCache = usedCache;
        this.rewardApplier = rewardApplier;
        this.pendingRewards = pendingRewards;
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
            Chat.send(player, config.prefix(), "&cHãy chờ một chút rồi thử lại.");
            return;
        }

        if (!GiftCodeGenerator.isValidFormat(code)) {
            Chat.send(player, config.prefix(), config.msgInvalidCode());
            return;
        }
        if (usedCache.isUsed(code)) {
            Chat.send(player, config.prefix(), config.msgAlreadyUsed());
            return;
        }
        if (!api.isConfigured()) {
            Chat.send(player, config.prefix(),
                    "&cKhông thể kết nối BanhmiVN.fun (chưa cấu hình api.key). Liên hệ Admin.");
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
                plugin.getLogger().warning("Redeem trả về 2xx nhưng body rỗng: " + code);
                Chat.send(player, config.prefix(), "&cCó lỗi xảy ra khi xác nhận mã. Thử lại sau ít phút.");
                return;
            }

            // Website đã atomic đánh dấu USED → trao đồ.
            usedCache.markUsed(code, player.getName());
            List<CodeItem> failed = rewardApplier.apply(player, response.getItems());
            if (!failed.isEmpty()) {
                // Trao lại khi player vào server lần sau.
                pendingRewards.add(player.getName(), failed);
                Chat.send(player, config.prefix(),
                        "&eMột số phần thưởng chưa trao được, sẽ tự động nhận khi bạn vào lại server.");
            }
            Chat.send(player, config.prefix(), config.msgRedeemSuccess());
            plugin.getLogger().info(
                    "Giftcode redeemed: " + code + " by " + player.getName()
                            + " items=" + response.getItems());
        });
    }

    private void handleRedeemError(Player player, String code, Throwable err) {
        if (err instanceof ApiException apiErr) {
            if (apiErr.isAlreadyUsed()) {
                // Website xác nhận mã đã dùng (hoặc đơn bị từ chối) → cache lại.
                usedCache.markUsed(code, player.getName());
                Chat.send(player, config.prefix(), config.msgAlreadyUsed());
                return;
            }
            if (apiErr.isNotFound()) {
                Chat.send(player, config.prefix(), config.msgInvalidCode());
                return;
            }
            plugin.getLogger().log(Level.WARNING,
                    "Redeem thất bại code=" + code + " player=" + player.getName(), apiErr);
            Chat.send(player, config.prefix(), "&cCó lỗi xảy ra khi xác nhận mã. Thử lại sau ít phút.");
            return;
        }
        plugin.getLogger().log(Level.WARNING,
                "Network lỗi khi redeem code=" + code + " player=" + player.getName(), err);
        Chat.send(player, config.prefix(), "&cKhông kết nối được server BanhmiVN. Thử lại sau.");
    }

    // ── Generate + Sync ─────────────────────────────────────

    /**
     * Sinh code mới + đăng ký lên website. Callback chạy trên main thread.
     */
    public void generateAndSync(org.bukkit.command.CommandSender sender,
                                String productType, String productName, int qty) {
        if (!api.isConfigured()) {
            Chat.send(sender, config.prefix(), "&cChưa cấu hình api.key — không thể sync code.");
            return;
        }
        String code = generator.generate();
        CodeSyncRequest request = new CodeSyncRequest(
                code, sender instanceof Player p ? p.getName() : null,
                List.of(new CodeItem(productType, productName, qty)));

        api.syncCode(request).whenComplete((resp, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        plugin.getLogger().log(Level.WARNING, "Sync code thất bại: " + code, err);
                        Chat.send(sender, config.prefix(),
                                "&cKhông đăng ký được code lên website: "
                                        + (err instanceof ApiException a ? a.getDetail() : "lỗi mạng"));
                        return;
                    }
                    Chat.send(sender, config.prefix(),
                            "&aĐã tạo giftcode &f" + code
                                    + "&a (" + qty + "x " + productName + ") — đã đồng bộ lên website.");
                }));
    }

    public GiftCodeGenerator generator() {
        return generator;
    }
}
