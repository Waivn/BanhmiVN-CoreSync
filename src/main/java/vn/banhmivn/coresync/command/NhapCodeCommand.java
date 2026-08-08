package vn.banhmivn.coresync.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import vn.banhmivn.coresync.config.PluginConfig;
import vn.banhmivn.coresync.giftcode.GiftCodeManager;
import vn.banhmivn.coresync.util.Chat;

/** Lệnh {@code /nhapcode <code>} (alias {@code /claim}) — người chơi nhập giftcode. */
public class NhapCodeCommand implements CommandExecutor {

    private final PluginConfig config;
    private final GiftCodeManager giftCodeManager;

    public NhapCodeCommand(PluginConfig config, GiftCodeManager giftCodeManager) {
        this.config = config;
        this.giftCodeManager = giftCodeManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Chỉ người chơi trong game mới nhập được giftcode.");
            return true;
        }
        if (!player.hasPermission("banhmivn.redeem")) {
            Chat.send(player, config.prefix(), "&cBạn không có quyền sử dụng lệnh này.");
            return true;
        }
        if (args.length < 1) {
            Chat.send(player, config.prefix(), "&cSử dụng: /" + label + " <code>");
            return true;
        }
        giftCodeManager.redeem(player, args[0]);
        return true;
    }
}
