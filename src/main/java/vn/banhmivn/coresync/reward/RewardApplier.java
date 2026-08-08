package vn.banhmivn.coresync.reward;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import vn.banhmivn.coresync.api.dto.CodeItem;
import vn.banhmivn.coresync.config.PluginConfig;
import vn.banhmivn.coresync.item.ItemBindingManager;
import vn.banhmivn.coresync.rank.RankType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Trao phần thưởng trong game (PHẢI chạy trên main thread).
 *
 * <ul>
 *   <li>{@code rank} → LuckPerms (API hoặc lệnh console {@code lp user <p> parent set <group>}),
 *       chỉ với rank trong enum {@link RankType} (STRICT).</li>
 *   <li>{@code point} → PlayerPoints (API {@code give(uuid, qty)} hoặc lệnh {@code p give}).</li>
 *   <li>{@code land} → GriefPrevention: lệnh console {@code adjustbonusclaimblocks <p> <qty>}.</li>
 *   <li>{@code crate} / {@code item} → item đã bind trong items.yml (key
 *       {@code crate:<name>} hoặc {@code <name>}).</li>
 * </ul>
 *
 * <p>Trả về danh sách reward KHÔNG trao được (để lưu pending, thử lại khi player
 * vào lại). Mọi lệnh console đều dùng group/giá trị đã validate — chống injection.
 */
public class RewardApplier {

    private final Plugin plugin;
    private final PluginConfig config;
    private final ItemBindingManager itemBinding;

    public RewardApplier(Plugin plugin, PluginConfig config, ItemBindingManager itemBinding) {
        this.plugin = plugin;
        this.config = config;
        this.itemBinding = itemBinding;
    }

    /**
     * Áp dụng danh sách reward.
     *
     * @return danh sách reward chưa trao được (player offline / lỗi / chưa bind item).
     */
    public List<CodeItem> apply(Player player, List<CodeItem> items) {
        List<CodeItem> failed = new ArrayList<>();
        for (CodeItem item : items) {
            try {
                if (!applyOne(player, item)) {
                    failed.add(item);
                }
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING,
                        "Lỗi khi trao reward " + item + " cho " + player.getName(), ex);
                failed.add(item);
            }
        }
        return failed;
    }

    private boolean applyOne(Player player, CodeItem item) {
        switch (item.getProductType() == null ? "" : item.getProductType().toLowerCase(Locale.ROOT)) {
            case "rank":
                return applyRank(player, item);
            case "point":
                return applyPoints(player, item.getQty());
            case "land":
                return applyClaimBlocks(player, item.getQty());
            case "crate":
            case "item":
                return applyBoundItem(player, item);
            default:
                plugin.getLogger().warning("Bỏ qua reward không hỗ trợ: " + item);
                return false;
        }
    }

    // ── Rank (LuckPerms) ────────────────────────────────────

    private boolean applyRank(Player player, CodeItem item) {
        java.util.Optional<RankType> rankOpt = RankType.fromProductName(item.getProductName());
        if (rankOpt.isEmpty()) {
            plugin.getLogger().warning(
                    "Từ chối rank không hợp lệ cho " + player.getName() + ": '" + item.getProductName() + "'"
                            + " (chỉ chấp nhận vip, vip_plus, svip)");
            return false;
        }
        String group = config.rankGroups().getOrDefault(rankOpt.get(), rankOpt.get().group());

        if (config.rankUseApi() && hasLuckPerms()) {
            return applyRankViaApi(player, group);
        }
        return dispatchConsole("lp user " + player.getName() + " parent set " + group);
    }

    private boolean applyRankViaApi(Player player, String group) {
        try {
            LuckPerms lp = LuckPermsProvider.get();
            // Kiểm tra nhóm tồn tại trước khi gán (5.4: add node cho group không tồn
            // tại sẽ im lặng thất bại; kiểm tra sớm để báo rõ thay vì "mất" rank).
            if (lp.getGroupManager().getGroup(group) == null) {
                plugin.getLogger().warning(
                        "Nhóm LuckPerms '" + group + "' không tồn tại — từ chối trao rank cho " + player.getName());
                return false;
            }
            // Chạy async trong LP executor; .join() chờ hoàn tất (thường <100ms).
            lp.getUserManager().modifyUser(player.getUniqueId(), user -> {
                // Tương đương `parent set <group>`: xoá mọi parent group, gán group mới,
                // rồi set primary group.
                // API LuckPerms 5.4: group membership = InheritanceNode (không có GroupNode).
                user.data().clear(NodeType.INHERITANCE.predicate(n -> !n.getGroupName().equalsIgnoreCase("default")));
                user.data().add(Node.builder(group).build());
                user.setPrimaryGroup(group);
            }).join();
            return true;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING,
                    "LuckPerms API thất bại cho " + player.getName() + " — thử lệnh console", ex);
            return dispatchConsole("lp user " + player.getName() + " parent set " + group);
        }
    }

    private boolean hasLuckPerms() {
        return Bukkit.getPluginManager().getPlugin("LuckPerms") != null;
    }

    // ── Points (PlayerPoints) ───────────────────────────────

    private boolean applyPoints(Player player, int qty) {
        if (qty <= 0) {
            return false;
        }
        if (config.pointsUseApi() && Bukkit.getPluginManager().getPlugin("PlayerPoints") != null) {
            try {
                return giveViaPlayerPointsApi(player, qty);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING,
                        "PlayerPoints API thất bại cho " + player.getName() + " — thử lệnh console", ex);
            }
        }
        return dispatchConsole("p give " + player.getName() + " " + qty);
    }

    /**
     * Gọi PlayerPoints API qua reflection — không khai báo dependency cứng nên
     * không phụ thuộc repo bên thứ ba và chạy được với mọi bản PlayerPoints:
     * v2 (PlayerPointsAPI.getAPI()) lẫn v3 (PlayerPoints.getInstance().getAPI()).
     */
    private boolean giveViaPlayerPointsApi(Player player, int qty) throws Exception {
        Class<?> apiClass = Class.forName("org.black_ixx.playerpoints.PlayerPointsAPI");
        Object api;
        try {
            // v3+: PlayerPoints.getInstance().getAPI()
            Class<?> mainClass = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
            Object instance = mainClass.getMethod("getInstance").invoke(null);
            api = mainClass.getMethod("getAPI").invoke(instance);
        } catch (ClassNotFoundException | NoSuchMethodException ex) {
            // v2: PlayerPointsAPI.getAPI() (static)
            api = apiClass.getMethod("getAPI").invoke(null);
        }
        Object result = apiClass.getMethod("give", java.util.UUID.class, int.class)
                .invoke(api, player.getUniqueId(), qty);
        return !(result instanceof Boolean) || (Boolean) result;
    }

    // ── Claim Blocks (GriefPrevention) ──────────────────────

    private boolean applyClaimBlocks(Player player, int qty) {
        if (qty <= 0) {
            return false;
        }
        return dispatchConsole("adjustbonusclaimblocks " + player.getName() + " " + qty);
    }

    // ── Bound items (crate / item) ──────────────────────────

    private boolean applyBoundItem(Player player, CodeItem item) {
        if (!config.giveItemOnRedeem()) {
            return false;
        }
        String name = normalizeItemKey(item.getProductName());
        String crateKey = "crate:" + name;
        String key = itemBinding.has(crateKey) ? crateKey
                : itemBinding.has(name) ? name : null;
        if (key == null) {
            plugin.getLogger().warning(
                    "Không tìm thấy item đã bind cho reward " + item + " — chạy /bmvn binditem "
                            + crateKey + " với item cầm trên tay.");
            return false;
        }
        return itemBinding.give(player, key, item.getQty()) > 0;
    }

    private String normalizeItemKey(String name) {
        if (name == null) {
            return "";
        }
        // Bỏ emoji/tiền tố như "🎁 Crate PREMIUM" → "premium"
        String cleaned = name.replaceAll("[^\\p{L}\\p{N} _\\-]", "")
                .replaceAll("(?i)crate", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        return cleaned.replaceAll("\\s+", "_");
    }

    // ── Console dispatch ────────────────────────────────────

    private boolean dispatchConsole(String command) {
        try {
            CommandSender console = Bukkit.getConsoleSender();
            return Bukkit.dispatchCommand(console, command);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Lệnh console thất bại: /" + command, ex);
            return false;
        }
    }
}
