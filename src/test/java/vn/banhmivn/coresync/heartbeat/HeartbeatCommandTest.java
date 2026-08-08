package vn.banhmivn.coresync.heartbeat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whitelist lệnh website được phép yêu cầu — chỉ "exportaudit" và "importaudit".
 * Bảo vệ phòng thủ: kể cả khi website bị lộ key, plugin không bao giờ chạy
 * lệnh tuỳ ý từ web.
 */
class HeartbeatCommandTest {

    @Test
    void onlyWhitelistedCommandsAreSupported() {
        assertTrue(HeartbeatService.isSupportedCommand("exportaudit"));
        assertTrue(HeartbeatService.isSupportedCommand("importaudit"));
        assertFalse(HeartbeatService.isSupportedCommand("exportaudit "), "không trim lệnh");
        assertFalse(HeartbeatService.isSupportedCommand("EXPORTAUDIT"), "case-sensitive");
        assertFalse(HeartbeatService.isSupportedCommand("IMPORTAUDIT"), "case-sensitive");
        assertFalse(HeartbeatService.isSupportedCommand("importaudit confirm"), "không nhận tham số");
        assertFalse(HeartbeatService.isSupportedCommand("shutdown"));
        assertFalse(HeartbeatService.isSupportedCommand("stop"));
        assertFalse(HeartbeatService.isSupportedCommand("deop admin"));
        assertFalse(HeartbeatService.isSupportedCommand(""));
        assertFalse(HeartbeatService.isSupportedCommand(null));
    }
}
