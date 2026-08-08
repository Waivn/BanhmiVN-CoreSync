package vn.banhmivn.coresync.export;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mã hoá AES-256-GCM nội dung snapshot trước khi đẩy lên website
 * (encryption at rest — website chỉ lưu bản mã hoá).
 *
 * <p>Định dạng payload (tự mô tả — website nhận diện qua magic, không cần
 * cột DB mới):
 * <pre>
 *   "BMVNENC1" (7 byte ASCII) || IV (12 byte) || ciphertext || GCM tag (16 byte)
 * </pre>
 *
 * <p>Key: 32 byte (AES-256), cấu hình dạng base64 qua {@code exports.encryption-key}
 * (phải KHỚP với {@code SNAPSHOT_ENCRYPTION_KEY} trên website). Mỗi lần mã hoá
 * dùng một IV ngẫu nhiên mới (GCM tuyệt đối không tái dùng IV).
 *
 * <p>GCM là authenticated encryption: nếu ai sửa ciphertext hoặc key sai,
 * tag không khớp → giải mã thất bại (website trả 502).
 */
public final class SnapshotCipher {

    /** Magic đánh dấu blob mã hoá — khớp với website (app/services/snapshot_crypto.py). */
    public static final byte[] MAGIC = "BMVNENC1".getBytes(StandardCharsets.US_ASCII);
    public static final int IV_LENGTH = 12;
    public static final int TAG_LENGTH = 16;
    private static final int KEY_LENGTH = 32;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

    private final SecretKeySpec key;

    public SnapshotCipher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Key AES-256 phải đúng " + KEY_LENGTH + " byte (nhận " + (keyBytes == null ? 0 : keyBytes.length) + ")");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** Tạo từ chuỗi base64 (decode ra đúng 32 byte). */
    public static SnapshotCipher fromBase64(String base64) {
        try {
            return new SnapshotCipher(BASE64_DECODER.decode(base64));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "exports.encryption-key không hợp lệ (cần base64 của 32 byte AES-256): " + ex.getMessage(), ex);
        }
    }

    /**
     * Mã hoá nội dung snapshot thành {@code MAGIC || IV || ciphertext || tag}.
     *
     * @throws IllegalStateException nếu provider không hỗ trợ AES/GCM
     *         (Java 8u161+ trở lên) — caller nên fail-loud, không đẩy bản rõ.
     */
    /** Mã hoá với IV ngẫu nhiên mới (API chính thức). */
    public byte[] encrypt(byte[] plaintext) {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        return encrypt(plaintext, iv);
    }

    /**
     * Mã hoá với IV chỉ định (package-private — chỉ dùng cho test known-answer).
     * Payload: {@code MAGIC || IV || ciphertext || tag}.
     */
    byte[] encrypt(byte[] plaintext, byte[] iv) {
        if (iv == null || iv.length != IV_LENGTH) {
            throw new IllegalArgumentException("IV phải đúng " + IV_LENGTH + " byte");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] out = cipher.doFinal(plaintext);

            byte[] result = new byte[MAGIC.length + IV_LENGTH + out.length];
            System.arraycopy(MAGIC, 0, result, 0, MAGIC.length);
            System.arraycopy(iv, 0, result, MAGIC.length, IV_LENGTH);
            System.arraycopy(out, 0, result, MAGIC.length + IV_LENGTH, out.length);
            return result;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Mã hoá snapshot thất bại (AES/GCM không khả dụng?)", ex);
        }
    }
}
