package vn.banhmivn.coresync.rank;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankTypeTest {

    @Test
    void mapsWebsiteProductNames() {
        assertEquals(Optional.of(RankType.VIP), RankType.fromProductName("👑 Rank VIP"));
        assertEquals(Optional.of(RankType.VIP_PLUS), RankType.fromProductName("👑 Rank VIP+"));
        assertEquals(Optional.of(RankType.SVIP), RankType.fromProductName("👑 Rank SVIP"));
    }

    @Test
    void mapsPlainAndAliasForms() {
        assertEquals(Optional.of(RankType.VIP), RankType.fromProductName("vip"));
        assertEquals(Optional.of(RankType.VIP_PLUS), RankType.fromProductName("vip_plus"));
        assertEquals(Optional.of(RankType.VIP_PLUS), RankType.fromProductName("vip-plus"));
        assertEquals(Optional.of(RankType.VIP_PLUS), RankType.fromProductName("vipplus"));
        assertEquals(Optional.of(RankType.VIP_PLUS), RankType.fromProductName("VIP +"));
        assertEquals(Optional.of(RankType.SVIP), RankType.fromProductName(" SVIP "));
    }

    @Test
    void rejectsUnknownRanksStrictly() {
        // KHÔNG bao giờ được trả về một rank → lệnh LuckPerms không chạy với group lạ
        assertFalse(RankType.fromProductName("👑 Rank GOD").isPresent());
        assertFalse(RankType.fromProductName("👑 Rank LEGEND").isPresent());
        assertFalse(RankType.fromProductName("premium").isPresent());
        assertFalse(RankType.fromProductName("admin").isPresent());
        assertFalse(RankType.fromProductName("owner").isPresent());
        assertFalse(RankType.fromProductName("").isPresent());
        assertFalse(RankType.fromProductName(null).isPresent());
    }

    @Test
    void groupNamesAreCleanForCommandExecution() {
        assertEquals("vip", RankType.VIP.group());
        assertEquals("vip_plus", RankType.VIP_PLUS.group());
        assertEquals("svip", RankType.SVIP.group());
    }

    @Test
    void fromGroupAcceptsConfigValues() {
        assertTrue(RankType.fromGroup("vip_plus").isPresent());
        assertTrue(RankType.fromGroup("VIP+").isPresent());
        assertFalse(RankType.fromGroup("god").isPresent());
    }
}
