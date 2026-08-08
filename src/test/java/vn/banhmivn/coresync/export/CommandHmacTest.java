package vn.banhmivn.coresync.export;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chữ ký HMAC-SHA256 của lệnh web (exportaudit/importaudit) — chống giả mạo
 * kênh pending-command khi MC_API_KEY bị lộ. KAT khớp vector sinh bởi Python
 * (website) nên hai phía chắc chắn nói cùng một định dạng canonical.
 */
class CommandHmacTest {

    // Vector sinh bởi Python: hmac.new(base64.b64decode(KEY), canonical, sha256)
    // canonical = "main\nexportaudit\n2026-08-08T10:00:00+00:00\n\nadmin@banhmivn.fun"
    private static final String KEY_B64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String SIG = "XfEbMElbsDUMKL4UYm4EzH7iRYvhdRAPzhKVJ3jPUz8=";
    private static final String CREATED = "2026-08-08T10:00:00+00:00";
    private static final String REQUESTER = "admin@banhmivn.fun";

    private static byte[] keyOf() {
        return CommandHmac.keyFromBase64(KEY_B64);
    }

    private static String sign(String server, String command, String created,
                               String fileB64, String requestedBy) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyOf(), "HmacSHA256"));
            byte[] digest = mac.doFinal(CommandHmac.canonical(server, command, created, fileB64, requestedBy)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void katMatchesPythonVector() {
        byte[] key = keyOf();
        assertEquals("main\nexportaudit\n" + CREATED + "\n\n" + REQUESTER,
                CommandHmac.canonical("main", "exportaudit", CREATED, null, REQUESTER));
        assertTrue(CommandHmac.verify(key, "main", "exportaudit", CREATED, null, REQUESTER, SIG));
    }

    @Test
    void canonicalIncludesFileB64AndRequester() {
        // Importaudit: file_b64 + requested_by nằm trong canonical → sửa file hoặc
        // người yêu cầu đều làm hỏng chữ ký.
        assertEquals("main\nimportaudit\n" + CREATED + "\nZm9v\n" + REQUESTER,
                CommandHmac.canonical("main", "importaudit", CREATED, "Zm9v", REQUESTER));
        String sig = sign("main", "importaudit", CREATED, "Zm9v", REQUESTER);
        assertTrue(CommandHmac.verify(keyOf(), "main", "importaudit", CREATED, "Zm9v", REQUESTER, sig));
        assertFalse(CommandHmac.verify(keyOf(), "main", "importaudit", CREATED, null, REQUESTER, sig),
                "bỏ file_b64");
        assertFalse(CommandHmac.verify(keyOf(), "main", "importaudit", CREATED, "Zm9v", "other@x", sig),
                "đổi requested_by");
    }

    @Test
    void rejectsTamperedFields() {
        byte[] key = keyOf();
        assertFalse(CommandHmac.verify(key, "EVIL", "exportaudit", CREATED, null, REQUESTER, SIG), "server đổi");
        assertFalse(CommandHmac.verify(key, "main", "shutdown", CREATED, null, REQUESTER, SIG), "command đổi");
        assertFalse(CommandHmac.verify(key, "main", "exportaudit", CREATED + "1", null, REQUESTER, SIG), "created_at đổi");
        assertFalse(CommandHmac.verify(key, "main", "exportaudit", CREATED, "Zm9v", REQUESTER, SIG), "file_b64 chèn");
        assertFalse(CommandHmac.verify(key, "main", "exportaudit", CREATED, null, "other@x", SIG), "requested_by đổi");
        assertFalse(CommandHmac.verify(key, "main", "exportaudit", CREATED, null, REQUESTER, SIG + "A"), "sig đổi");
        assertFalse(CommandHmac.verify(key, "main", "exportaudit", CREATED, null, REQUESTER, null), "sig thiếu");
        assertFalse(CommandHmac.verify(key, "main", "exportaudit", CREATED, null, REQUESTER, ""), "sig rỗng");
    }

    @Test
    void wrongKeyRejected() {
        byte[] other = Base64.getDecoder().decode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        assertFalse(CommandHmac.verify(other, "main", "exportaudit", CREATED, null, REQUESTER, SIG));
    }

    @Test
    void legacyNullKeyAlwaysAccepts() {
        // Plugin chưa cấu hình secret → giữ hành vi cũ (không ký), không phá vỡ nâng cấp.
        assertTrue(CommandHmac.verify(null, "main", "exportaudit", "x", null, null, null));
        assertTrue(CommandHmac.verify(null, "EVIL", "shutdown", "x", "file", "someone", "garbage"));
    }

    @Test
    void shortOrMalformedKeyRejected() {
        String shortB64 = Base64.getEncoder().encodeToString("short".getBytes());
        assertThrows(IllegalArgumentException.class, () -> CommandHmac.keyFromBase64(shortB64));
        assertThrows(IllegalArgumentException.class, () -> CommandHmac.keyFromBase64("not base64!!"));
    }
}
