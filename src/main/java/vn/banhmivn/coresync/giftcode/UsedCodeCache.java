package vn.banhmivn.coresync.giftcode;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Cache cục bộ các mã đã dùng (file {@code used-codes.yml}).
 *
 * <p>Mục đích: (1) chặn người chơi thử lại mã đã dùng mà không cần gọi API,
 * (2) ghi nhận trạng thái USED ngay cả khi website không thể truy cập.
 * Chỉ ghi nhận code đã được xác nhận USED bởi website (200/409/410) — không
 * cache mã invalid để tránh chặn nhầm.
 */
public class UsedCodeCache {

    private final File file;
    private final Map<String, UsedEntry> used = new LinkedHashMap<>();

    public record UsedEntry(String player, long claimedAt) {
    }

    public UsedCodeCache(Plugin plugin) {
        this.file = new File(plugin.getDataFolder(), "used-codes.yml");
        load();
    }

    @SuppressWarnings("unchecked")
    public void load() {
        used.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("used");
        if (section == null) {
            return;
        }
        for (String code : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(code);
            if (entry == null) {
                continue;
            }
            used.put(code, new UsedEntry(
                    entry.getString("player", ""),
                    entry.getLong("at", 0L)
            ));
        }
    }

    public boolean isUsed(String code) {
        return used.containsKey(code);
    }

    public synchronized void markUsed(String code, String player) {
        used.put(code, new UsedEntry(player == null ? "" : player, System.currentTimeMillis()));
        save();
    }

    public int size() {
        return used.size();
    }

    public Map<String, UsedEntry> entries() {
        return Map.copyOf(used);
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, UsedEntry> e : used.entrySet()) {
            String path = "used." + e.getKey();
            yaml.set(path + ".player", e.getValue().player());
            yaml.set(path + ".at", e.getValue().claimedAt());
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            file.getAbsoluteFile().getParentFile().mkdirs();
            try {
                yaml.save(file);
            } catch (IOException ex2) {
                // nghiêm trọng: mất cache → vẫn hoạt động, chỉ thử lại API
                java.util.logging.Logger.getLogger("BanhmiVN-CoreSync")
                        .log(Level.SEVERE, "Không thể lưu used-codes.yml", ex2);
            }
        }
    }
}
