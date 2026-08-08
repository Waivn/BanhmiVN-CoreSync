package vn.banhmivn.coresync.alert;

/**
 * Một quy tắc phát hiện hành vi đáng ngờ.
 *
 * <p>Khi số lần ghi nhận event {@code event} từ cùng một player trong khoảng
 * {@code windowSeconds} giây đạt {@code threshold} lần → bắn cảnh báo, nhưng
 * tối đa một cảnh báo cho mỗi {@code cooldownSeconds} giây (chống spam staff).
 *
 * @param name            tên quy tắc (hiển thị trong cảnh báo, ví dụ "redeem-invalid")
 * @param event           tên event audit cần theo dõi (ví dụ "REDEEM_INVALID")
 * @param windowSeconds   cửa sổ thời gian (giây) đếm số lần xuất hiện
 * @param threshold       số lần tối thiểu trong cửa sổ để kích hoạt cảnh báo
 * @param cooldownSeconds tối thiểu (giây) giữa hai cảnh báo cùng quy tắc + player
 * @param enabled         false → quy tắc bị tắt, không đếm cũng không cảnh báo
 */
public record AlertRule(
        String name,
        String event,
        long windowSeconds,
        int threshold,
        long cooldownSeconds,
        boolean enabled) {

    public AlertRule {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("rule name must not be blank");
        }
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("rule event must not be blank");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        if (cooldownSeconds < 0) {
            throw new IllegalArgumentException("cooldownSeconds must not be negative");
        }
    }
}
