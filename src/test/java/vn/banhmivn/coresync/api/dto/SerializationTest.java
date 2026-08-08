package vn.banhmivn.coresync.api.dto;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm thử payload serialization/deserialization với JSON mẫu CHÍNH XÁC
 * trả về từ website (schema trong app/schemas.py + app/routers/codes.py).
 */
class SerializationTest {

    private static final Gson GSON = new Gson();

    // ── Deserialize: /api/codes/redeem response (khớp CodeRedeemResponse bên web) ──

    @Test
    void redeemResponseDeserializesExactly() {
        String json = """
                {
                  "success": true,
                  "message": "Mã hợp lệ — đã trao phần thưởng cho người chơi",
                  "status": "used",
                  "order_code": "DHV260808123456ABC123",
                  "user_id": 42,
                  "user_ign": "Steve",
                  "redeemed_by": "Steve",
                  "total": 100000.0,
                  "items": [
                    {"product_type": "rank", "product_name": "👑 Rank VIP+", "qty": 1},
                    {"product_type": "point", "product_name": "💎 Đổi Point Server", "qty": 500}
                  ]
                }
                """;

        CodeRedeemResponse resp = GSON.fromJson(json, CodeRedeemResponse.class);

        assertTrue(resp.isSuccess());
        assertEquals("used", resp.getStatus());
        assertEquals(42L, resp.getUserId());
        assertEquals("Steve", resp.getUserIgn());
        assertEquals(2, resp.getItems().size());

        CodeItem rank = resp.getItems().get(0);
        assertEquals("rank", rank.getProductType());
        assertEquals("👑 Rank VIP+", rank.getProductName());
        assertEquals(1, rank.getQty());
    }

    @Test
    void redeemResponseHandlesEmptyItems() {
        String json = """
                {"success": true, "message": "ok", "status": "used", "items": []}
                """;
        CodeRedeemResponse resp = GSON.fromJson(json, CodeRedeemResponse.class);
        assertNotNull(resp.getItems());
        assertTrue(resp.getItems().isEmpty());
    }

    // ── Serialize: request payloads (phải đúng tên field snake_case website) ──

    @Test
    void redeemRequestUsesSnakeCaseFieldNames() {
        String json = GSON.toJson(new CodeRedeemRequest("BMVN-ABCD-EFGH-JKLM", "Steve"));
        assertTrue(json.contains("\"code\""), json);
        assertTrue(json.contains("\"ign\""), json);
        assertTrue(json.contains("\"player_name\""), json);
        assertFalse(json.contains("playerName"), "Phải dùng snake_case player_name: " + json);
        assertFalse(json.contains("player-name"), json);
    }

    @Test
    void syncRequestUsesSnakeCaseAndShipsItems() {
        CodeSyncRequest req = new CodeSyncRequest(
                "BMVN-ABCD-EFGH-JKLM", "Steve",
                List.of(new CodeItem("point", "💎 Đổi Point Server", 100)));
        String json = GSON.toJson(req);
        assertTrue(json.contains("\"player_name\":\"Steve\""), json);
        assertTrue(json.contains("\"product_type\":\"point\""), json);
        assertTrue(json.contains("\"product_name\""), json);
    }

    @Test
    void syncRequestOmitsNullPlayerName() {
        // Website chấp nhận player_name optional — null phải được bỏ qua.
        String json = GSON.toJson(new CodeSyncRequest("BMVN-ABCD-EFGH-JKLM", null, List.of()));
        assertFalse(json.contains("player_name"), json);
    }

    // ── Serialize: server status telemetry ──

    @Test
    void statusPayloadOmitsNullFields() {
        ServerStatusPayload payload = new ServerStatusPayload(
                "online", null, 12, 100, 19.98, null, 512L);
        String json = GSON.toJson(payload);
        assertTrue(json.contains("\"status\":\"online\""), json);
        assertTrue(json.contains("\"player_count\":12"), json);
        assertTrue(json.contains("\"max_players\":100"), json);
        assertTrue(json.contains("\"tps\":19.98"), json);
        assertTrue(json.contains("\"memory_mb\":512"), json);
        assertFalse(json.contains("\"message\""), "message null phải bị bỏ qua: " + json);
        assertFalse(json.contains("\"ping\""), "ping null phải bị bỏ qua: " + json);
    }

    // ── Deserialize: /api/export/pending response (web-triggered exportaudit) ──

    @Test
    void pendingCommandResponseParses() {
        String withCommand = "{\"server\":\"main\",\"command\":\"exportaudit\"}";
        PendingCommandResponse resp = GSON.fromJson(withCommand, PendingCommandResponse.class);
        assertEquals("exportaudit", resp.getCommand());
        assertEquals("main", resp.getServer());
        assertEquals(null, resp.getFileB64(), "exportaudit không kèm file");

        String empty = "{\"server\":\"main\",\"command\":null}";
        PendingCommandResponse none = GSON.fromJson(empty, PendingCommandResponse.class);
        assertEquals(null, none.getCommand(), "không có lệnh chờ → command null");
    }

    @Test
    void pendingImportResponseCarriesFileB64() {
        // Website trả file_b64 khi lệnh là importaudit (snapshot .tar.gz admin upload)
        String json = "{\"server\":\"main\",\"command\":\"importaudit\","
                + "\"file_b64\":\"aGVsbG8td29ybGQ=\"}";
        PendingCommandResponse resp = GSON.fromJson(json, PendingCommandResponse.class);
        assertEquals("importaudit", resp.getCommand());
        assertEquals("aGVsbG8td29ybGQ=", resp.getFileB64());

        String noFile = "{\"server\":\"main\",\"command\":\"importaudit\",\"file_b64\":null}";
        assertEquals(null, GSON.fromJson(noFile, PendingCommandResponse.class).getFileB64());
    }

    @Test
    void statusPayloadRoundTrips() {
        ServerStatusPayload payload = new ServerStatusPayload(
                "maintenance", "Bảo trì", 0, 100, 20.0, null, 300L);
        String json = GSON.toJson(payload);
        ServerStatusPayload back = GSON.fromJson(json, ServerStatusPayload.class);
        assertEquals("maintenance", back.getStatus());
        assertEquals("Bảo trì", back.getMessage());
        assertEquals(0, back.getPlayerCount());
        assertEquals(100, back.getMaxPlayers());
        assertEquals(300L, back.getMemoryMb());
    }
}
