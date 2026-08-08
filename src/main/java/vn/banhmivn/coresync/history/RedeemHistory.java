package vn.banhmivn.coresync.history;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import vn.banhmivn.coresync.api.dto.CodeItem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Lịch sử redeem có cấu trúc ({@code redeem-history.yml}) phục vụ
 * lệnh {@code /bmvn history <player>}: player → danh sách mã đã đổi
 * (code, thời điểm, items). Audit trail thô nằm ở {@code audit.log};
 * store này chỉ là bản truy vấn nhanh theo player, giới hạn 100 mã/player.
 */
public class RedeemHistory {

    private static final int MAX_PER_PLAYER = 100;

    private final Plugin plugin;
    private final File file;
    private final Map<String, List<Record>> byPlayer = new LinkedHashMap<>();

    /** Một lần redeem thành công. */
    public record Record(String code, long at, List<CodeItem> items) {
    }

    public RedeemHistory(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "redeem-history.yml");
        load();
    }

    /** Thêm bản ghi (main thread). Giữ tối đa MAX_PER_PLAYER mã mới nhất mỗi player. */
    public synchronized void add(String player, String code, List<CodeItem> items) {
        String key = player.toLowerCase(Locale.ROOT);
        List<Record> list = byPlayer.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(new Record(code, System.currentTimeMillis(),
                items == null ? List.of() : new ArrayList<>(items)));
        while (list.size() > MAX_PER_PLAYER) {
            list.remove(0); // bỏ bản cũ nhất (bản mới nối đuôi)
        }
        save();
    }

    /** Lịch sử của player (mã mới nhất trước). Rỗng nếu chưa có. */
    public List<Record> get(String player) {
        List<Record> list = byPlayer.get(player.toLowerCase(Locale.ROOT));
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Record> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }

    public int totalRecords() {
        return byPlayer.values().stream().mapToInt(List::size).sum();
    }

    @SuppressWarnings("unchecked")
    /** Nạp lại từ disk (dùng sau khi /bmvn importaudit). */
    public synchronized void reload() {
        load();
    }

    private void load() {
        byPlayer.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("history");
        if (section == null) {
            return;
        }
        for (String player : section.getKeys(false)) {
            ConfigurationSection pSection = section.getConfigurationSection(player);
            if (pSection == null) {
                continue;
            }
            List<Record> list = new ArrayList<>();
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
                list.add(new Record(
                        rSection.getString("code", ""),
                        rSection.getLong("at", 0L),
                        items
                ));
            }
            byPlayer.put(player, list);
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, List<Record>> e : byPlayer.entrySet()) {
            String base = "history." + e.getKey();
            List<Record> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                Record r = list.get(i);
                String path = base + "." + i;
                yaml.set(path + ".code", r.code());
                yaml.set(path + ".at", r.at());
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
            plugin.getLogger().log(Level.SEVERE, "Không thể lưu redeem-history.yml", ex);
        }
    }
}
