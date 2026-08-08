package vn.banhmivn.coresync.reward;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import vn.banhmivn.coresync.api.dto.CodeItem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Reward chưa trao được (player offline / lỗi / item chưa bind) được lưu vào
 * {@code pending-rewards.yml} và tự động thử lại khi player vào server.
 * Mã đã USED trên web nhưng reward chưa tới tay → không mất quà.
 */
public class PendingRewards {

    private final Plugin plugin;
    private final File file;
    private final Map<String, List<Reward>> pending = new LinkedHashMap<>();

    public record Reward(List<CodeItem> items, long claimedAt) {
    }

    public PendingRewards(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending-rewards.yml");
        load();
    }

    public synchronized void add(String playerName, List<CodeItem> items) {
        String key = playerName.toLowerCase(Locale.ROOT);
        pending.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new Reward(items, System.currentTimeMillis()));
        save();
    }

    public synchronized List<Reward> takeAll(String playerName) {
        String key = playerName.toLowerCase(Locale.ROOT);
        List<Reward> list = pending.remove(key);
        if (list != null) {
            save();
        }
        return list == null ? List.of() : list;
    }

    public boolean hasPending(String playerName) {
        return pending.containsKey(playerName.toLowerCase(Locale.ROOT));
    }

    public Map<String, List<Reward>> all() {
        return Map.copyOf(pending);
    }

    @SuppressWarnings("unchecked")
    /** Nạp lại từ disk (dùng sau khi /bmvn importaudit). */
    public synchronized void reload() {
        load();
    }

    private void load() {
        pending.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("pending");
        if (section == null) {
            return;
        }
        for (String player : section.getKeys(false)) {
            ConfigurationSection pSection = section.getConfigurationSection(player);
            if (pSection == null) {
                continue;
            }
            List<Reward> list = new ArrayList<>();
            for (String idx : pSection.getKeys(false)) {
                ConfigurationSection rSection = pSection.getConfigurationSection(idx);
                if (rSection == null) {
                    continue;
                }
                List<CodeItem> items = new ArrayList<>();
                ConfigurationSection itemsSection = rSection.getConfigurationSection("items");
                if (itemsSection != null) {
                    for (String itemIdx : itemsSection.getKeys(false)) {
                        ConfigurationSection item = itemsSection.getConfigurationSection(itemIdx);
                        if (item == null) {
                            continue;
                        }
                        items.add(new CodeItem(
                                item.getString("type", ""),
                                item.getString("name", ""),
                                item.getInt("qty", 1)
                        ));
                    }
                }
                list.add(new Reward(items, rSection.getLong("at", 0L)));
            }
            pending.put(player, list);
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, List<Reward>> e : pending.entrySet()) {
            String base = "pending." + e.getKey();
            List<Reward> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                Reward r = list.get(i);
                String path = base + "." + i;
                yaml.set(path + ".at", r.claimedAt());
                List<CodeItem> items = r.items();
                for (int j = 0; j < items.size(); j++) {
                    CodeItem it = items.get(j);
                    String ip = path + ".items." + j;
                    yaml.set(ip + ".type", it.getProductType());
                    yaml.set(ip + ".name", it.getProductName());
                    yaml.set(ip + ".qty", it.getQty());
                }
            }
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Không thể lưu pending-rewards.yml", ex);
        }
    }
}
