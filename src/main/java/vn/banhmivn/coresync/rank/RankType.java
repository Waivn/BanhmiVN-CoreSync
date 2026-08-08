package vn.banhmivn.coresync.rank;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Danh sách rank HỢP LỆ duy nhất mà plugin chấp nhận trao (strict mapping).
 *
 * <p>Website lưu product_name dạng {@code "👑 Rank VIP"}, {@code "👑 Rank VIP+"},
 * {@code "👑 Rank SVIP"} — {@link #fromProductName(String)} chuẩn hoá chuỗi này.
 * Mọi rank khác → {@link Optional#empty()} và bị TỪ CHỐI (không bao giờ chạy
 * lệnh LuckPerms với group không nằm trong danh sách — chống syntax/injection).
 */
public enum RankType {
    VIP("vip"),
    VIP_PLUS("vip_plus", "vip-plus", "vip+", "vipplus"),
    SVIP("svip");

    private final String group;
    private final Set<String> aliases;

    RankType(String group, String... extraAliases) {
        this.group = group;
        Set<String> set = new java.util.HashSet<>();
        set.add(group);
        set.add(name().toLowerCase(Locale.ROOT));
        set.addAll(java.util.Arrays.asList(extraAliases));
        this.aliases = java.util.Collections.unmodifiableSet(set);
    }

    /** Tên nhóm LuckPerms (giá trị sạch, đã được kiểm tra). */
    public String group() {
        return group;
    }

    /**
     * Chuẩn hoá tên rank từ website (bỏ emoji/tiền tố) và ánh xạ sang enum.
     *
     * @param productName product_name của reward (vd "👑 Rank VIP+")
     * @return rank hợp lệ, hoặc empty nếu không nằm trong danh sách cho phép.
     */
    public static Optional<RankType> fromProductName(String productName) {
        if (productName == null) {
            return Optional.empty();
        }
        String norm = productName
                .replaceAll("[^\\p{L}\\p{N} +_\\-]", "") // bỏ emoji/ký tự lạ
                .replaceAll("(?i)rank", "")
                .replaceAll("\\s+", "")                     // bỏ khoảng trắng bên trong ("VIP +" → "vip+")
                .trim()
                .toLowerCase(Locale.ROOT);
        for (RankType r : values()) {
            if (r.aliases.contains(norm)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** Giống {@link #fromProductName(String)} nhưng nhận thẳng tên nhóm (vd "vip_plus"). */
    public static Optional<RankType> fromGroup(String group) {
        if (group == null) {
            return Optional.empty();
        }
        String norm = group.trim().toLowerCase(Locale.ROOT);
        for (RankType r : values()) {
            if (r.aliases.contains(norm) || r.group.equalsIgnoreCase(norm)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
