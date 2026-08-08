package vn.banhmivn.coresync.alert;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Bộ phát hiện hành vi đáng ngờ dựa trên cửa sổ trượt (sliding window).
 *
 * <p>Mỗi quy tắc ({@link AlertRule}) theo dõi một loại event audit. Với mỗi
 * player, plugin ghi lại thời điểm các event xuất hiện; khi số lần trong
 * {@code windowSeconds} đạt {@code threshold} → kích hoạt cảnh báo qua
 * {@link Sink#fireAlert}, đồng thời khóa cảnh báo trong {@code cooldownSeconds}
 * để không spam staff.
 *
 * <p>Hoàn toàn thuần Java (không phụ thuộc Bukkit) — unit-test được. An toàn
 * đa luồng: mỗi hàng đợi thời điểm được khóa bằng {@code synchronized}.
 */
public class SuspicionDetector {

    /** Điểm nhận cảnh báo (Discord/email/log). */
    public interface Sink {
        /**
         * @param title   tiêu đề ngắn (vd "Cảnh báo: Steve — redeem-invalid")
         * @param message nội dung chi tiết (player, số lần, cửa sổ, ...)
         */
        void fireAlert(String title, String message);
    }

    private final List<AlertRule> rules;
    private final Sink sink;
    private final LongSupplier clock;

    /** rule|player → danh sách thời điểm event (epoch ms) trong cửa sổ. */
    private final Map<String, Deque<Long>> events = new ConcurrentHashMap<>();
    /** rule|player → thời điểm cảnh báo gần nhất (epoch ms). */
    private final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    public SuspicionDetector(List<AlertRule> rules, Sink sink) {
        this(rules, sink, System::currentTimeMillis);
    }

    /** Package-private cho unit test (bơm đồng hồ giả). */
    SuspicionDetector(List<AlertRule> rules, Sink sink, LongSupplier clock) {
        this.rules = List.copyOf(rules);
        this.sink = sink;
        this.clock = clock;
    }

    /**
     * Ghi nhận một event audit.
     *
     * @param event  tên event (vd "REDEEM_INVALID")
     * @param player player liên quan (null/"-" cho console)
     * @param detail chi tiết kèm theo (vd "bad-format", "not-found")
     * @return true nếu event này đã kích hoạt cảnh báo
     */
    public boolean observe(String event, String player, String detail) {
        long now = clock.getAsLong();
        boolean fired = false;
        String who = (player == null || player.isBlank()) ? "-" : player;

        for (AlertRule rule : rules) {
            if (!rule.enabled() || !rule.event().equals(event)) {
                continue;
            }
            String key = rule.name() + "|" + who;
            Deque<Long> deque = events.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (deque) {
                deque.addLast(now);
                prune(deque, now, rule.windowSeconds() * 1000L);
                if (deque.size() >= rule.threshold() && canFire(rule, key, now)) {
                    int count = deque.size();
                    deque.clear(); // reset đếm sau khi cảnh báo
                    lastAlertAt.put(key, now);
                    fired = true;
                    sink.fireAlert(rule.name(), message(rule, who, count, detail));
                }
            }
        }
        return fired;
    }

    private void prune(Deque<Long> deque, long now, long windowMs) {
        while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) {
            deque.removeFirst();
        }
    }

    private boolean canFire(AlertRule rule, String key, long now) {
        Long prev = lastAlertAt.get(key);
        return prev == null || now - prev >= rule.cooldownSeconds() * 1000L;
    }

    private String message(AlertRule rule, String player, int count, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("Player ").append(player)
                .append(" thử ").append(count).append(" lần ").append(rule.event())
                .append(" trong ").append(rule.windowSeconds()).append('s')
                .append(" (ngưỡng ").append(rule.threshold()).append(')');
        if (detail != null && !detail.isBlank()) {
            sb.append(". Chi tiết gần nhất: ").append(detail);
        }
        return sb.toString();
    }
}
