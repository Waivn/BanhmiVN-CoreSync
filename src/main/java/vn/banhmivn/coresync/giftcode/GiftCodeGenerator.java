package vn.banhmivn.coresync.giftcode;

import java.security.SecureRandom;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Sinh Giftcode an toàn, đúng định dạng website:
 * {@code BMVN-XXXX-XXXX-XXXX}, bảng chữ cái loại bỏ I/O/L/0
 * (khớp {@code _new_redemption_code} trong app/routers/shop.py).
 */
public final class GiftCodeGenerator {

    /** Giống alphabet bên website shop.py. */
    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** Regex kiểm tra định dạng code hợp lệ (không phân biệt hoa thường — tầng trên chuẩn hoá UPPER). */
    public static final Pattern CODE_PATTERN =
            Pattern.compile("^BMVN-[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){2}$", Pattern.CASE_INSENSITIVE);

    private final Random random;

    public GiftCodeGenerator() {
        this(new SecureRandom());
    }

    /** Chỉ dùng cho test (đoán trước được kết quả). */
    GiftCodeGenerator(Random random) {
        this.random = random;
    }

    /** Sinh một code ngẫu nhiên an toàn: BMVN-XXXX-XXXX-XXXX. */
    public String generate() {
        return "BMVN-" + segment() + "-" + segment() + "-" + segment();
    }

    private String segment() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static boolean isValidFormat(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }
}
