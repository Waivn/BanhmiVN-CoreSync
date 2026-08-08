package vn.banhmivn.coresync.export;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC-SHA256 bảo vệ kênh pending-command (web → plugin).
 *
 * <p>Kênh hiện tại tin tưởng X-API-Key chia sẻ: nếu key lộ, kẻ tấn công (hoặc
 * một node khác) có thể chèn lệnh {@code exportaudit} giả mạo vào hàng đợi.
 * Để khoá nguồn, website ký từng lệnh bằng {@code HMAC-SHA256(secret,
 * canonical)} với secret chia sẻ (mặc định tái dùng snapshot encryption key),
 * plugin chỉ thực thi lệnh có chữ ký hợp lệ — canonical gồm đủ mọi trường dùng
 * để chạy lệnh (kể cả {@code file_b64} của importaudit) nên không sửa được
 * payload mà không làm hỏng chữ ký.
 *
 * <p>Canonical form (PHẢI khớp chính xác giữa Python website và Java plugin):
 * {@code server + "\n" + command + "\n" + created_at + "\n" + file_b64 + "\n" + requested_by}
 * (mỗi trường rỗng khi null). {@code requested_by} nằm trong vùng ký để kẻ
 * sửa DB không thể đổi người yêu cầu ghi vào audit trail mà không làm hỏng
 * chữ ký.
 *
 * <p>Chế độ legacy: nếu plugin chưa cấu hình secret ({@code null}), verify trả
 * {@code true} — giữ hành vi cũ (không ký) để nâng cấp không phá vỡ cấu hình
 * hiện có, kèm cảnh báo lúc reload. Khi plugin ĐÃ cấu hình secret mà lệnh
 * thiếu chữ ký / chữ ký sai → từ chối (fail-closed).
 */
public final class CommandHmac {

    private CommandHmac() {
    }

    /** Canonical form — khớp từng byte với bên website. */
    public static String canonical(String server, String command, String createdAt,
                                   String fileB64, String requestedBy) {
        return server + "\n" + command + "\n" + (createdAt == null ? "" : createdAt)
                + "\n" + (fileB64 == null ? "" : fileB64)
                + "\n" + (requestedBy == null ? "" : requestedBy);
    }

    /**
     * Giải mã base64 thành khoá HMAC. Yêu cầu ≥ 16 byte để tránh key yếu.
     *
     * @throws IllegalArgumentException base64 sai hoặc key quá ngắn
     */
    public static byte[] keyFromBase64(String base64Key) {
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length < 16) {
            throw new IllegalArgumentException("Khoá HMAC phải >= 16 byte sau khi giải mã base64");
        }
        return key;
    }

    /**
     * Xác thực chữ ký của lệnh.
     *
     * @param key khoá HMAC; {@code null} → chế độ legacy, luôn chấp nhận
     * @return {@code true} nếu chữ ký khớp (so sánh constant-time)
     */
    public static boolean verify(byte[] key, String server, String command,
                                 String createdAt, String fileB64, String requestedBy, String sig) {
        if (key == null) {
            return true; // legacy — plugin chưa cấu hình secret
        }
        if (sig == null || sig.isBlank()) {
            return false;
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            expected = mac.doFinal(canonical(server, command, createdAt, fileB64, requestedBy)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 không khả dụng", ex);
        }
        byte[] expectedB64 = Base64.getEncoder().encodeToString(expected).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedB64, sig.getBytes(StandardCharsets.UTF_8));
    }
}
