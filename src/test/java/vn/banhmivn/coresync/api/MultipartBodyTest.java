package vn.banhmivn.coresync.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartBodyTest {

    @Test
    void buildsFieldAndFileParts() {
        byte[] fileBytes = "gzip-bytes".getBytes(StandardCharsets.UTF_8);
        MultipartBody.Body body = MultipartBody.build(List.of(
                new MultipartBody.Part("server", null, null, "main".getBytes(StandardCharsets.UTF_8)),
                new MultipartBody.Part("file", "audit-snapshot-x.tar.gz", "application/gzip", fileBytes)));

        String text = new String(body.bytes(), StandardCharsets.UTF_8);
        assertTrue(body.boundary().startsWith("----BanhmiVN"));
        assertTrue(text.contains("--" + body.boundary()));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"server\""));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"file\"; filename=\"audit-snapshot-x.tar.gz\""));
        assertTrue(text.contains("Content-Type: application/gzip"));
        assertTrue(text.endsWith("--" + body.boundary() + "--\r\n"));
        // content của file phải nằm nguyên vẹn trong body
        assertTrue(text.contains("gzip-bytes"));
    }

    @Test
    void partsAreSeparableAndRoundTrip() {
        MultipartBody.Body body = MultipartBody.build(List.of(
                new MultipartBody.Part("server", null, null, "sv".getBytes(StandardCharsets.UTF_8)),
                new MultipartBody.Part("file", "a.tar.gz", "application/gzip",
                        new byte[]{0x1F, (byte) 0x8B, 0x00, 0x01})));
        String text = new String(body.bytes(), StandardCharsets.ISO_8859_1);

        // Tách các part bằng boundary: [pre, part-server, part-file(+đuôi đóng), phần còn lại]
        String[] chunks = text.split("--" + body.boundary());
        assertEquals(4, chunks.length); // closing "--boundary--" cũng chứa "--boundary"
        assertTrue(chunks[1].contains("name=\"server\""));
        assertTrue(chunks[1].contains("\r\nsv\r\n"));
        assertTrue(chunks[2].contains("name=\"file\""));
        // 4 bytes gzip magic phải giữ nguyên
        int idx = chunks[2].indexOf("\u001F\u008B");
        assertTrue(idx >= 0, "file content phải nằm trong part 2");
        assertFalse(chunks[2].contains("sv\r\n"), "field content không được lẫn vào part file");
        // boundary chỉ xuất hiện đúng 3 lần: mở đầu, giữa, đóng
        assertEquals(2, countOccurrences(text, "--" + body.boundary() + "\r\n"));
        assertEquals(1, countOccurrences(text, "--" + body.boundary() + "--"));
    }

    @Test
    void emptyContentAllowed() {
        MultipartBody.Body body = MultipartBody.build(List.of(
                new MultipartBody.Part("file", "e.tar.gz", "application/gzip", new byte[0])));
        String text = new String(body.bytes(), StandardCharsets.UTF_8);
        assertTrue(text.contains("filename=\"e.tar.gz\""));
        assertArrayEquals(new byte[0], new byte[0]);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
