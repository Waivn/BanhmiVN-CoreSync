package vn.banhmivn.coresync.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

/** Tiny helper for colored, prefixed chat messages. */
public final class Chat {

    private Chat() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static String prefixed(String prefix, String message) {
        return color(prefix + message);
    }

    /** Sends a prefixed message to a sender (prefix is the plugin message prefix). */
    public static void send(CommandSender sender, String prefix, String message) {
        sender.sendMessage(prefixed(prefix, message));
    }

    public static void sendList(CommandSender sender, String prefix, List<String> lines) {
        for (String line : lines) {
            sender.sendMessage(prefixed(prefix, line));
        }
    }
}
