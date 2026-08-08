package vn.banhmivn.coresync.giftcode;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiftCodeGeneratorTest {

    @Test
    void formatMatchesWebsitePattern() {
        GiftCodeGenerator gen = new GiftCodeGenerator(new Random(42));
        for (int i = 0; i < 500; i++) {
            String code = gen.generate();
            assertTrue(GiftCodeGenerator.isValidFormat(code),
                    "Code sai định dạng: " + code);
            assertEquals("BMVN-XXXX-XXXX-XXXX".length(), code.length());
        }
    }

    @Test
    void alphabetExcludesAmbiguousCharacters() {
        GiftCodeGenerator gen = new GiftCodeGenerator(new Random(7));
        for (int i = 0; i < 1000; i++) {
            String code = gen.generate();
            for (char c : code.toCharArray()) {
                if (c == '-') {
                    continue;
                }
                // Không được chứa I/O/L/0 (giống website, chống nhầm lẫn)
                assertFalse("IOL0".indexOf(c) >= 0, "Code chứa ký tự dễ nhầm: " + code);
                assertTrue(GiftCodeGenerator.ALPHABET.indexOf(c) >= 0, "Ký tự lạ: " + c);
            }
        }
    }

    @Test
    void generatedCodesAreUnique() {
        GiftCodeGenerator gen = new GiftCodeGenerator();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(gen.generate()), "Code bị trùng!");
        }
    }

    @Test
    void validateFormatEdgeCases() {
        assertTrue(GiftCodeGenerator.isValidFormat("BMVN-ABCD-EFGH-JKLM"));
        assertTrue(GiftCodeGenerator.isValidFormat("bmvn-abcd-efgh-jklm")); // thường hoá ở tầng trên
        assertFalse(GiftCodeGenerator.isValidFormat("BMVN-ABCD-EFGH"));
        assertFalse(GiftCodeGenerator.isValidFormat("BMVN-ABCD-EFGH-JKLM-X"));
        assertFalse(GiftCodeGenerator.isValidFormat("BAL-ABCD-EFGH"));       // loại khác
        assertFalse(GiftCodeGenerator.isValidFormat("BMVN-AIO0-EFGH-JKLM")); // ký tự cấm
        assertFalse(GiftCodeGenerator.isValidFormat(null));
        assertFalse(GiftCodeGenerator.isValidFormat(""));
    }
}
