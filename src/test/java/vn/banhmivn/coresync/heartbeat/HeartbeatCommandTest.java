package vn.banhmivn.coresync.heartbeat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whitelist lệnh website được phép yêu cầu — chỉ "exportaudit".
 * Bảo vệ phòng thủ: kể cả khi website bị lộ key, plugin không bao giờ chạy
 * lệnh tuỳ ý từ web.
 */
class HeartbeatCommandTest {

    @Test
    void onlyExportauditIsSupported() {
        assertTrue(HeartbeatService.isSupportedCommand("exportaudit"));
        assertFalse(HeartbeatService.isSupportedCommand("exportaudit "), "không trim lệnh");
        assertFalse(HeartbeatService.isSupportedCommand("EXPORTAUDIT"), "case-sensitive");
        assertFalse(HeartbeatService.isSupportedCommand("shutdown"));
        assertFalse(HeartbeatService.isSupportedCommand("stop"));
        assertFalse(HeartbeatService.isSupportedCommand("deop admin"));
        assertFalse(HeartbeatService.isSupportedCommand(""));
        assertFalse(HeartbeatService.isSupportedCommand(null));
    }
}
