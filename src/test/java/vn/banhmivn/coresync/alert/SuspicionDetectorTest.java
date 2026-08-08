package vn.banhmivn.coresync.alert;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuspicionDetectorTest {

    /** Đồng hồ giả — test kiểm soát thời gian tuyệt đối. */
    private static final class Clock implements java.util.function.LongSupplier {
        long now = 0;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    /** Sink đếm + ghi lại cảnh báo. */
    private static final class CountingSink implements SuspicionDetector.Sink {
        int fired;
        String lastTitle;
        String lastMessage;

        @Override
        public void fireAlert(String title, String message) {
            fired++;
            lastTitle = title;
            lastMessage = message;
        }
    }

    private static final AlertRule RULE = new AlertRule(
            "redeem-invalid", "REDEEM_INVALID", 60, 5, 300, true);

    private static final class Harness {
        final Clock clock = new Clock();
        final CountingSink sink = new CountingSink();
        final SuspicionDetector detector;

        Harness(AlertRule... rules) {
            detector = new SuspicionDetector(List.of(rules), sink, clock);
        }
    }

    /** N lần thử code sai ở cùng thời điểm (hoặc cách nhau step ms). */
    private static void spam(SuspicionDetector detector, Clock clock,
                             String player, int n, long stepMs, String detail) {
        for (int i = 0; i < n; i++) {
            detector.observe("REDEEM_INVALID", player, detail);
            clock.advance(stepMs);
        }
    }

    @Test
    void belowThresholdNoAlert() {
        Harness h = new Harness(RULE);
        spam(h.detector, h.clock, "Steve", 4, 1000, "not-found");
        assertEquals(0, h.sink.fired);
    }

    @Test
    void thresholdFiresExactlyOnceWithDetails() {
        Harness h = new Harness(RULE);
        spam(h.detector, h.clock, "Steve", 5, 1000, "not-found");
        assertEquals(1, h.sink.fired);
        assertEquals("redeem-invalid", h.sink.lastTitle);
        assertTrue(h.sink.lastMessage.contains("Steve"));
        assertTrue(h.sink.lastMessage.contains("5 lần"));
        assertTrue(h.sink.lastMessage.contains("REDEEM_INVALID"));
        assertTrue(h.sink.lastMessage.contains("60"));
    }

    @Test
    void eventsSpreadBeyondWindowNoAlert() {
        Harness h = new Harness(RULE);
        // 4 lần trong 4s, sau đó 65s im lặng rồi thử tiếp — cửa sổ đã trôi qua
        spam(h.detector, h.clock, "Steve", 4, 1000, "not-found");
        h.clock.advance(65_000);
        h.detector.observe("REDEEM_INVALID", "Steve", "not-found");
        assertEquals(0, h.sink.fired);
    }

    @Test
    void windowSlidesAndPrunesOldEvents() {
        Harness h = new Harness(RULE);
        // 4 lần → chưa đủ, chờ hết cửa sổ → các event cũ bị loại
        spam(h.detector, h.clock, "Steve", 4, 1000, "not-found");
        assertEquals(0, h.sink.fired);
        h.clock.advance(61_000);
        // 5 lần nhanh trong cửa sổ mới → đủ ngưỡng
        spam(h.detector, h.clock, "Steve", 5, 1000, "not-found");
        assertEquals(1, h.sink.fired);
    }

    @Test
    void cooldownSuppressesRepeatThenAllowsAgain() {
        Harness h = new Harness(RULE);
        spam(h.detector, h.clock, "Steve", 5, 1000, "bad-format");
        assertEquals(1, h.sink.fired);
        // Ngay sau đó lại thử 5 lần — trong cooldown → không cảnh báo thêm
        spam(h.detector, h.clock, "Steve", 5, 1000, "bad-format");
        assertEquals(1, h.sink.fired);
        // Hết cooldown 300s → 5 lần nữa → cảnh báo lần 2
        h.clock.advance(301_000);
        spam(h.detector, h.clock, "Steve", 5, 1000, "bad-format");
        assertEquals(2, h.sink.fired);
    }

    @Test
    void otherEventIgnored() {
        Harness h = new Harness(RULE);
        for (int i = 0; i < 5; i++) {
            h.detector.observe("GENERATE", "Steve", "");
        }
        assertEquals(0, h.sink.fired);
    }

    @Test
    void disabledRuleNeverAlerts() {
        Harness h = new Harness(new AlertRule("off", "REDEEM_INVALID", 60, 5, 300, false));
        spam(h.detector, h.clock, "Steve", 10, 1000, "not-found");
        assertEquals(0, h.sink.fired);
    }

    @Test
    void onlyMatchingRuleFiresAmongSeveral() {
        Harness h = new Harness(
                new AlertRule("a", "REDEEM_INVALID", 60, 5, 300, true),
                new AlertRule("b", "REDEEM_FAIL", 60, 5, 300, true));
        spam(h.detector, h.clock, "Steve", 5, 1000, "not-found");
        assertEquals(1, h.sink.fired);
        assertEquals("a", h.sink.lastTitle);
    }

    @Test
    void differentPlayersTrackedIndependently() {
        Harness h = new Harness(RULE);
        spam(h.detector, h.clock, "Steve", 5, 1000, "not-found");
        spam(h.detector, h.clock, "Notch", 5, 1000, "not-found");
        assertEquals(2, h.sink.fired);
    }

    @Test
    void consoleSenderKeyedSeparately() {
        Harness h = new Harness(RULE);
        spam(h.detector, h.clock, "-", 5, 1000, "not-found");
        assertEquals(1, h.sink.fired);
    }

    @Test
    void observeReturnsWhetherAlertFired() {
        Harness h = new Harness(RULE);
        boolean firedOnFirst = h.detector.observe("REDEEM_INVALID", "Steve", "bad-format");
        assertFalse(firedOnFirst);
        spam(h.detector, h.clock, "Steve", 3, 1000, "bad-format");
        // Đã có 4 event (t=0..3000) — event thứ 5 phải kích hoạt cảnh báo
        boolean firedOnFifth = h.detector.observe("REDEEM_INVALID", "Steve", "bad-format");
        assertTrue(firedOnFifth);
        assertEquals(1, h.sink.fired);
    }
}
