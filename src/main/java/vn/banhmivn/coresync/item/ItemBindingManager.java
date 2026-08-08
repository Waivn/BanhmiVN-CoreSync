package vn.banhmivn.coresync.item;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Hệ thống bind item: admin cầm item trên tay chạy
 * {@code /bmvn binditem <key>} → lưu toàn bộ ItemMeta (NBT, enchant, lore,
 * display name) vào {@code items.yml}. Khi player redeem code liên kết với key,
 * item chính xác đó được trao vào inventory (rớt dưới chân nếu đầy).
 */
public class ItemBindingManager {

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9_\\-]{1,64}");

    private final Plugin plugin;
    private final File file;
    private final Map<String, String> store = new LinkedHashMap<>(); // key -> base64 ItemStack

    public ItemBindingManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "items.yml");
        load();
    }

    /** Bind item đang cầm trên tay vào key. Trả null nếu thành công, else thông báo lỗi. */
    public String bind(Player admin, String key) {
        key = normalizeKey(key);
        if (key == null) {
            return "Key không hợp lệ (chỉ a-z, 0-9, -, _ — tối đa 64 ký tự).";
        }
        ItemStack item = admin.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            return "Bạn phải cầm một item trên tay để bind.";
        }
        String base64 = serialize(item);
        if (base64 == null) {
            return "Không thể lưu item này (lỗi serialize). Thử item khác.";
        }
        store.put(key, base64);
        save();
        return null;
    }

    public boolean unbind(String key) {
        key = normalizeKey(key);
        if (key == null) {
            return false;
        }
        boolean removed = store.remove(key) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public boolean has(String key) {
        return store.containsKey(normalizeKey(key));
    }

    /** Trao item đã bind cho player; rớt dưới chân nếu inventory đầy. Trả số item đã trao. */
    public int give(Player player, String key, int qty) {
        if (qty <= 0) {
            return 0;
        }
        ItemStack template = getItemStack(key);
        if (template == null) {
            return 0;
        }
        int given = 0;
        for (int i = 0; i < qty; i++) {
            ItemStack copy = template.clone();
            if (tryAdd(player, copy)) {
                given++;
            }
        }
        return given;
    }

    private boolean tryAdd(Player player, ItemStack item) {
        int firstEmpty = player.getInventory().firstEmpty();
        if (firstEmpty < 0) {
            // Inventory đầy → rớt dưới chân
            player.getWorld().dropItemNaturally(player.getLocation(), item);
            return true;
        }
        player.getInventory().setItem(firstEmpty, item);
        return true;
    }

    public ItemStack getItemStack(String key) {
        String base64 = store.get(normalizeKey(key));
        return base64 == null ? null : deserialize(base64);
    }

    public List<String> keys() {
        return new ArrayList<>(store.keySet());
    }

    public int size() {
        return store.size();
    }

    /** Key chuẩn hoá (lowercase) hoặc null nếu không hợp lệ. */
    private String normalizeKey(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim().toLowerCase();
        return KEY_PATTERN.matcher(k).matches() ? k : null;
    }

    // ── Serialization ──────────────────────────────────────

    private String serialize(ItemStack item) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
                out.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Không thể serialize item", ex);
            return null;
        }
    }

    private ItemStack deserialize(String base64) {
        try {
            ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
            try (BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
                Object obj = in.readObject();
                return obj instanceof ItemStack item ? item : null;
            }
        } catch (IOException | ClassNotFoundException ex) {
            plugin.getLogger().log(Level.SEVERE, "Không thể deserialize item (có thể server đổi version)", ex);
            return null;
        }
    }

    // ── Persistence ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    /** Nạp lại từ disk (dùng sau khi /bmvn importaudit). */
    public void reload() {
        load();
    }

    private void load() {
        store.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("items");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            store.put(key, section.getString(key));
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, String> e : store.entrySet()) {
            yaml.set("items." + e.getKey(), e.getValue());
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Không thể lưu items.yml", ex);
        }
    }
}
