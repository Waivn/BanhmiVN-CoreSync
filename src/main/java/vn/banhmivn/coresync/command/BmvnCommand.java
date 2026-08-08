package vn.banhmivn.coresync.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import vn.banhmivn.coresync.api.dto.CodeItem;
import vn.banhmivn.coresync.audit.AuditLogger;
import vn.banhmivn.coresync.config.PluginConfig;
import vn.banhmivn.coresync.history.RedeemHistory;
import vn.banhmivn.coresync.giftcode.GiftCodeManager;
import vn.banhmivn.coresync.heartbeat.HeartbeatService;
import vn.banhmivn.coresync.item.ItemBindingManager;
import vn.banhmivn.coresync.rank.RankType;
import vn.banhmivn.coresync.util.Chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lệnh admin {@code /bmvn}:
 *
 * <ul>
 *   <li>{@code binditem <key>} — bind item đang cầm vào key (items.yml)</li>
 *   <li>{@code unbinditem <key>} — xoá binding</li>
 *   <li>{@code listitems} — danh sách key đã bind</li>
 *   <li>{@code giveitem <key> <player> [qty]} — trao trực tiếp item đã bind</li>
 *   <li>{@code code <rank|point|land|item|crate> <value> [qty]} — sinh giftcode + sync web</li>
 *   <li>{@code history <player>} — lịch sử các mã player đã redeem</li>
 *   <li>{@code status} — trạng thái heartbeat/telemetry</li>
 *   <li>{@code sync} — đẩy heartbeat ngay lập tức</li>
 *   <li>{@code reload} — nạp lại config.yml</li>
 * </ul>
 */
public class BmvnCommand implements CommandExecutor, TabCompleter {

    private final vn.banhmivn.coresync.BanhmiVNCoreSync plugin;
    private final PluginConfig config;
    private final GiftCodeManager giftCodeManager;
    private final ItemBindingManager itemBinding;
    private final HeartbeatService heartbeat;

    public BmvnCommand(vn.banhmivn.coresync.BanhmiVNCoreSync plugin, PluginConfig config,
                       GiftCodeManager giftCodeManager, ItemBindingManager itemBinding,
                       HeartbeatService heartbeat) {
        this.plugin = plugin;
        this.config = config;
        this.giftCodeManager = giftCodeManager;
        this.itemBinding = itemBinding;
        this.heartbeat = heartbeat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("banhmivn.admin")) {
            Chat.send(sender, config.prefix(), "&cBạn không có quyền.");
            return true;
        }
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "binditem" -> bindItem(sender, args);
            case "unbinditem" -> unbindItem(sender, args);
            case "listitems" -> listItems(sender);
            case "giveitem" -> giveItem(sender, args);
            case "code" -> generateCode(sender, args);
            case "history" -> showHistory(sender, args);
            case "status" -> showStatus(sender);
            case "sync" -> {
                heartbeat.tick();
                Chat.send(sender, config.prefix(), "&aĐã đẩy heartbeat (xem kết quả: /bmvn status).");
            }
            case "reload" -> {
                plugin.reloadAll();
                Chat.send(sender, config.prefix(), "&aĐã reload config.");
            }
            default -> showHelp(sender);
        }
        return true;
    }

    // ── Subcommands ─────────────────────────────────────────

    private void bindItem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage("Lệnh binditem phải chạy trong game (cầm item trên tay).");
            return;
        }
        if (args.length < 2) {
            Chat.send(sender, config.prefix(), "&cSử dụng: /bmvn binditem <key>");
            return;
        }
        String error = itemBinding.bind(admin, args[1]);
        if (error != null) {
            Chat.send(sender, config.prefix(), "&c" + error);
            return;
        }
        Chat.send(sender, config.prefix(),
                "&aĐã bind item " + admin.getInventory().getItemInMainHand().getType()
                        + " vào key &f" + args[1].toLowerCase(Locale.ROOT));
    }

    private void unbindItem(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Chat.send(sender, config.prefix(), "&cSử dụng: /bmvn unbinditem <key>");
            return;
        }
        boolean ok = itemBinding.unbind(args[1]);
        Chat.send(sender, config.prefix(),
                ok ? "&aĐã xoá key &f" + args[1].toLowerCase(Locale.ROOT)
                        : "&cKhông tìm thấy key &f" + args[1]);
    }

    private void listItems(CommandSender sender) {
        List<String> keys = itemBinding.keys();
        if (keys.isEmpty()) {
            Chat.send(sender, config.prefix(), "&7Chưa có item nào được bind.");
            return;
        }
        Chat.send(sender, config.prefix(), "&eDanh sách item đã bind (&f" + keys.size() + "&e):");
        for (String key : keys) {
            var stack = itemBinding.getItemStack(key);
            sender.sendMessage(Chat.color(config.prefix() + "&7 - &f" + key
                    + " &8(&7" + (stack == null ? "?" : stack.getType()) + "&8)"));
        }
    }

    private void giveItem(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Chat.send(sender, config.prefix(), "&cSử dụng: /bmvn giveitem <key> <player> [qty]");
            return;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        if (!itemBinding.has(key)) {
            Chat.send(sender, config.prefix(), "&cKhông tìm thấy key &f" + key);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            Chat.send(sender, config.prefix(), "&cPlayer &f" + args[2] + " &ckhông online.");
            return;
        }
        int qty = args.length >= 4 ? parseInt(args[3], 1) : 1;
        int given = itemBinding.give(target, key, qty);
        Chat.send(sender, config.prefix(),
                "&aĐã trao &f" + given + "x &akey &f" + key + " &acho &f" + target.getName());
    }

    private void generateCode(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Chat.send(sender, config.prefix(),
                    "&cSử dụng: /bmvn code <rank|point|land|item|crate> <value> [qty]");
            return;
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        String value = args[2];
        int qty = args.length >= 4 ? parseInt(args[3], 1) : 1;

        switch (type) {
            case "rank" -> {
                Optional<RankType> rankOpt = RankType.fromGroup(value);
                if (rankOpt.isEmpty()) {
                    Chat.send(sender, config.prefix(),
                            "&cRank không hợp lệ. Chỉ chấp nhận: vip, vip+ (vip_plus), svip.");
                    return;
                }
                giftCodeManager.generateAndSync(sender, "rank", rankDisplay(rankOpt.get()), 1);
            }
            case "point" -> {
                if (qty <= 0) {
                    Chat.send(sender, config.prefix(), "&cSố lượng point phải > 0.");
                    return;
                }
                giftCodeManager.generateAndSync(sender, "point", "💎 Đổi Point Server", qty);
            }
            case "land" -> {
                if (qty <= 0) {
                    Chat.send(sender, config.prefix(), "&cSố claim blocks phải > 0.");
                    return;
                }
                giftCodeManager.generateAndSync(sender, "land", "🏠 Mua Claim Đất", qty);
            }
            case "item", "crate" -> {
                String key = value.toLowerCase(Locale.ROOT);
                String crateKey = "crate:" + key;
                String bound = itemBinding.has(crateKey) ? crateKey
                        : itemBinding.has(key) ? key : null;
                if (bound == null) {
                    Chat.send(sender, config.prefix(),
                            "&cChưa bind item cho key này. Chạy: /bmvn binditem " + crateKey);
                    return;
                }
                giftCodeManager.generateAndSync(sender, type, key, Math.max(1, qty));
            }
            default -> Chat.send(sender, config.prefix(),
                    "&cLoại không hợp lệ: " + type + " (rank|point|land|item|crate)");
        }
    }

    private void showHistory(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Chat.send(sender, config.prefix(), "&cSử dụng: /bmvn history <player>");
            return;
        }
        List<RedeemHistory.Record> records = plugin.redeemHistory().get(args[1]);
        if (records.isEmpty()) {
            Chat.send(sender, config.prefix(), "&7Chưa có lịch sử redeem nào cho &f" + args[1] + "&7.");
            return;
        }
        Chat.send(sender, config.prefix(),
                "&eLịch sử giftcode của &f" + args[1] + "&e (&f" + records.size() + "&e mã):");
        int shown = 0;
        java.text.SimpleDateFormat timeFmt = new java.text.SimpleDateFormat("dd/MM HH:mm");
        for (RedeemHistory.Record rec : records) {
            if (shown >= 20) {
                sender.sendMessage(Chat.color(config.prefix()
                        + "&7...và " + (records.size() - shown) + " mã cũ hơn (xem đầy đủ trong redeem-history.yml)."));
                break;
            }
            sender.sendMessage(Chat.color(config.prefix()
                    + "&7 - &f" + rec.code()
                    + " &8(&7" + timeFmt.format(new java.util.Date(rec.at())) + "&8)"
                    + " &e" + AuditLogger.itemsToString(rec.items())));
            shown++;
        }
        sender.sendMessage(Chat.color(config.prefix() + "&7Trail đầy đủ: &f" + plugin.auditLogger().file().getName()));
    }

    private void showStatus(CommandSender sender) {
        HeartbeatService.LastResult last = heartbeat.lastResult();
        Chat.send(sender, config.prefix(), "&e— BanhmiVN-CoreSync status —");
        sender.sendMessage(Chat.color(config.prefix() + "&7 Server: &f" + config.serverId() + " (&f" + config.serverName() + "&7)"));
        sender.sendMessage(Chat.color(config.prefix() + "&7 State:  &f" + config.serverState().name()
                + " &8→ web status: &f" + config.serverState().webStatus()));
        sender.sendMessage(Chat.color(config.prefix() + "&7 Heartbeat interval: &f" + config.heartbeatIntervalSeconds() + "s"));
        sender.sendMessage(Chat.color(config.prefix() + "&7 Last push: &f" + (last.success() ? "&aOK" : "&cFAIL")
                + " &7(" + (last.at() == null ? "n/a" : last.at().toString()) + ") "
                + (last.detail() == null || last.detail().isBlank() ? "" : " — " + last.detail())));
        sender.sendMessage(Chat.color(config.prefix() + "&7 Bound items: &f" + itemBinding.size()
                + " &7| Used codes cached: &f" + plugin.usedCache().size()
                + " &7| API configured: &f" + plugin.apiClient().isConfigured()));
    }

    private void showHelp(CommandSender sender) {
        Chat.send(sender, config.prefix(), "&e— /bmvn —");
        for (String line : List.of(
                "&7/bmvn binditem &f<key>",
                "&7/bmvn unbinditem &f<key>",
                "&7/bmvn listitems",
                "&7/bmvn giveitem &f<key> <player> [qty]",
                "&7/bmvn code &f<rank|point|land|item|crate> <value> [qty]",
                "&7/bmvn history &f<player>",
                "&7/bmvn status | sync | reload")) {
            sender.sendMessage(Chat.color(config.prefix() + line));
        }
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String rankDisplay(RankType rank) {
        return switch (rank) {
            case VIP -> "👑 Rank VIP";
            case VIP_PLUS -> "👑 Rank VIP+";
            case SVIP -> "👑 Rank SVIP";
        };
    }

    // ── Tab completion ──────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("banhmivn.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("binditem", "unbinditem", "listitems", "giveitem",
                    "code", "history", "status", "sync", "reload"), args[0]);
        }
        if (args.length == 2 && "history".equalsIgnoreCase(args[0])) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "unbinditem", "giveitem" -> {
                    List<String> keys = new ArrayList<>(itemBinding.keys());
                    return filter(keys, args[1]);
                }
                case "code" -> {
                    return filter(List.of("rank", "point", "land", "item", "crate"), args[1]);
                }
                default -> {
                }
            }
        }
        if (args.length == 3 && "code".equalsIgnoreCase(args[0])) {
            if ("rank".equalsIgnoreCase(args[1])) {
                return filter(List.of("vip", "vip_plus", "svip"), args[2]);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
