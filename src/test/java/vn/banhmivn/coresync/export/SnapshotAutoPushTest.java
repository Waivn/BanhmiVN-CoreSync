package vn.banhmivn.coresync.export;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kiểm tra logic thuần của SnapshotAutoPush (phần Bukkit scheduler không test được). */
class SnapshotAutoPushTest {

    @Test
    void enabledOnlyWhenIntervalPushAndApiAllTrue() {
        assertTrue(SnapshotAutoPush.isEnabled(6, true, true));
        assertFalse(SnapshotAutoPush.isEnabled(0, true, true), "interval 0 = tắt");
        assertFalse(SnapshotAutoPush.isEnabled(-1, true, true), "interval âm = tắt");
        assertFalse(SnapshotAutoPush.isEnabled(6, false, true), "push-to-website tắt");
        assertFalse(SnapshotAutoPush.isEnabled(6, true, false), "api.key chưa cấu hình");
        assertFalse(SnapshotAutoPush.isEnabled(0, false, false));
    }

    @Test
    void tickMathIsHoursTimesTicksPerHour() {
        assertEquals(72_000L, SnapshotAutoPush.ticksForHours(1));
        assertEquals(432_000L, SnapshotAutoPush.ticksForHours(6));
        assertEquals(864_000L, SnapshotAutoPush.ticksForHours(12));
        assertEquals(0L, SnapshotAutoPush.ticksForHours(0));
        // Chu kỳ 24h vẫn nằm trong phạm vi int tick của Bukkit (2^31 ≈ 68 năm @20tps)
        assertEquals(1_728_000L, SnapshotAutoPush.ticksForHours(24));
    }
}
