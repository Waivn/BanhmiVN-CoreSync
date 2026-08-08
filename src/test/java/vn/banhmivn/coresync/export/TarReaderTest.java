package vn.banhmivn.coresync.export;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TarReaderTest {

    private static byte[] tarOf(int[] entrySizes) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        TarWriter tar = new TarWriter(raw);
        for (int size : entrySizes) {
            tar.writeEntry("f" + size + ".bin", new byte[size], 1_700_000_000L);
        }
        tar.finish();
        return raw.toByteArray();
    }

    @Test
    void perEntrySizeCapRejected() throws IOException {
        byte[] raw = tarOf(new int[]{150});
        // giới hạn entry 100 bytes — entry 150 bytes phải bị từ chối
        assertThrows(IOException.class, () ->
                new TarReader(100, 100_000, 10)
                        .read(new ByteArrayInputStream(raw)));
    }

    @Test
    void totalSizeCapRejected() throws IOException {
        byte[] raw = tarOf(new int[]{60, 50});
        // 60 + 50 = 110 > tổng 100 → từ chối (dù từng entry đều dưới giới hạn 100)
        assertThrows(IOException.class, () ->
                new TarReader(100, 100, 10)
                        .read(new ByteArrayInputStream(raw)));
    }

    @Test
    void entryCountCapRejected() throws IOException {
        byte[] raw = tarOf(new int[]{1, 1, 1});
        assertThrows(IOException.class, () ->
                new TarReader(1_000, 100_000, 2)
                        .read(new ByteArrayInputStream(raw)));
    }

    @Test
    void defaultReaderAcceptsNormalArchive() throws IOException {
        byte[] raw = tarOf(new int[]{3, 5});
        Map<String, byte[]> entries = new TarReader().read(new ByteArrayInputStream(raw));
        assertEquals(2, entries.size());
        assertArrayEquals(new byte[5], entries.get("f5.bin"));
    }

    @Test
    void readerRoundTripsTextContent() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        TarWriter tar = new TarWriter(raw);
        tar.writeEntry("audit.log", "hello".getBytes(StandardCharsets.UTF_8), 1_700_000_000L);
        tar.finish();
        Map<String, byte[]> entries = new TarReader().read(new ByteArrayInputStream(raw.toByteArray()));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), entries.get("audit.log"));
    }
}
