package vn.banhmivn.coresync.export;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm tra SnapshotCipher. Known-answer test (KAT) cross-validate với Python:
 * cùng key/IV/plaintext dùng cryptography AESGCM → cùng ciphertext+tag,
 * chứng minh plugin (Java) và website (Python) nói chung một định dạng.
 */
class SnapshotCipherTest {

    /** key = 00..1F (32 byte), iv = 00..0B (12 byte), pt = "banhmi" */
    private static final byte[] KEY = range(0, 32);
    private static final byte[] IV = range(0, 12);
    private static final byte[] PLAINTEXT = "banhmi".getBytes(StandardCharsets.US_ASCII);

    /** Sinh bởi: AESGCM(key).encrypt(iv, b"banhmi", None) — đã chạy thử với cryptography. */
    private static final String EXPECTED_IV_CT = "000102030405060708090a0b"
            + "2563b873a88c74772ae27669e0e2d35dd61d914e1ee2";
    private static final String EXPECTED_BLOB = "424d564e454e4331" + EXPECTED_IV_CT; // "BMVNENC1" hex

    @Test
    void knownAnswerMatchesPythonVector() {
        byte[] blob = new SnapshotCipher(KEY).encrypt(PLAINTEXT, IV);
        assertArrayEquals(HexFormat.of().parseHex(EXPECTED_BLOB), blob);
    }

    @Test
    void formatHasMagicIvThenCiphertextTag() {
        byte[] blob = new SnapshotCipher(KEY).encrypt(PLAINTEXT);
        assertTrue(Arrays.equals(SnapshotCipher.MAGIC, Arrays.copyOf(blob, SnapshotCipher.MAGIC.length)),
                "phải bắt đầu bằng magic BMVNENC1");
        assertArrayEquals(SnapshotCipher.MAGIC, Arrays.copyOfRange(blob, 0, SnapshotCipher.MAGIC.length));
        assertTrue(blob.length == SnapshotCipher.MAGIC.length + SnapshotCipher.IV_LENGTH
                + PLAINTEXT.length + SnapshotCipher.TAG_LENGTH, "7 + 12 + n + 16");
    }

    @Test
    void roundTripEncryptDecrypt() throws Exception {
        byte[] blob = new SnapshotCipher(KEY).encrypt(PLAINTEXT);
        assertArrayEquals(PLAINTEXT, decryptLocal(blob, KEY));
    }

    @Test
    void roundTripEmptyAndLargerPayloads() throws Exception {
        SnapshotCipher cipher = new SnapshotCipher(KEY);
        byte[] empty = cipher.encrypt(new byte[0]);
        assertArrayEquals(new byte[0], decryptLocal(empty, KEY));
        byte[] big = new byte[1_000_000]; // 1MB
        Arrays.fill(big, (byte) 7);
        assertArrayEquals(big, decryptLocal(cipher.encrypt(big), KEY));
    }

    @Test
    void randomIvMakesEveryBlobUnique() {
        SnapshotCipher cipher = new SnapshotCipher(KEY);
        byte[] a = cipher.encrypt(PLAINTEXT);
        byte[] b = cipher.encrypt(PLAINTEXT);
        assertFalse(Arrays.equals(a, b), "IV ngẫu nhiên → 2 lần mã hoá phải khác nhau");
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> new SnapshotCipher(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotCipher(null));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotCipher(new byte[64]));
    }

    @Test
    void fromBase64AcceptsValidKeyAndRejectsGarbage() throws Exception {
        String b64 = java.util.Base64.getEncoder().encodeToString(KEY);
        SnapshotCipher cipher = SnapshotCipher.fromBase64(b64);
        assertArrayEquals(PLAINTEXT, decryptLocal(cipher.encrypt(PLAINTEXT), KEY));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCipher.fromBase64("không phải base64!!"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotCipher.fromBase64("c2hvcnQ=")); // 5 byte
        assertThrows(IllegalArgumentException.class, () -> SnapshotCipher.fromBase64(""));
    }

    @Test
    void invalidIvRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotCipher(KEY).encrypt(PLAINTEXT, new byte[5]));
    }

    /** Giải mã cục bộ chỉ dùng trong test (production không cần decrypt). */
    private static byte[] decryptLocal(byte[] blob, byte[] key) throws Exception {
        assertTrue(Arrays.equals(SnapshotCipher.MAGIC,
                Arrays.copyOfRange(blob, 0, SnapshotCipher.MAGIC.length)));
        byte[] iv = Arrays.copyOfRange(blob, SnapshotCipher.MAGIC.length,
                SnapshotCipher.MAGIC.length + SnapshotCipher.IV_LENGTH);
        byte[] ct = Arrays.copyOfRange(blob, SnapshotCipher.MAGIC.length + SnapshotCipher.IV_LENGTH,
                blob.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        return cipher.doFinal(ct);
    }

    private static byte[] range(int from, int toExclusive) {
        byte[] out = new byte[toExclusive - from];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (from + i);
        }
        return out;
    }
}
