package vn.banhmivn.coresync.export;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reader TAR tối giản (ustar) — dùng cho {@code /bmvn importaudit}.
 *
 * <p>An toàn theo thiết kế (dữ liệu đến từ file bên ngoài):
 * <ul>
 *   <li>Chỉ chấp nhận tên entry <b>phẳng</b> — từ chối mọi tên chứa {@code /},
 *       {@code \}, {@code ..} (chống path traversal / ghi đè file ngoài ý muốn).</li>
 *   <li>Kiểm tra checksum header — dữ liệu hỏng bị từ chối ngay.</li>
 *   <li>Giới hạn kích thước mỗi entry, <b>tổng dung lượng giải nén</b> và số entry
 *       (kiểm tra trước khi cấp phát bộ nhớ) — chống tar-bomb làm OOM server.</li>
 * </ul>
 */
final class TarReader {

    private static final int BLOCK = 512;
    private static final long DEFAULT_MAX_ENTRY_BYTES = 128L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final int DEFAULT_MAX_ENTRIES = 64;

    private final long maxEntryBytes;
    private final long maxTotalBytes;
    private final int maxEntries;

    TarReader() {
        this(DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_ENTRIES);
    }

    /** Package-private: test có thể hạ giới hạn để kiểm tra chống tar-bomb. */
    TarReader(long maxEntryBytes, long maxTotalBytes, int maxEntries) {
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxEntries = maxEntries;
    }

    /** Đọc toàn bộ archive tar. Trả về map entry (giữ thứ tự gốc). */
    Map<String, byte[]> read(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] header = new byte[BLOCK];
        int count = 0;
        long totalBytes = 0;
        while (true) {
            readFully(in, header);
            if (allZero(header)) {
                break; // end-of-archive
            }
            String name = ascii(header, 0, 100).trim();
            if (name.isEmpty()) {
                break;
            }
            validateName(name);
            long size = parseOctal(header, 124);
            if (size < 0 || size > maxEntryBytes) {
                throw new IOException("Entry '" + name + "' có kích thước bất thường ("
                        + size + " bytes) — từ chối.");
            }
            // Kiểm tra tổng TRƯỚC khi cấp phát content — chống tar-bomb OOM.
            if (totalBytes + size > maxTotalBytes) {
                throw new IOException("Tổng dung lượng giải nén vượt giới hạn "
                        + maxTotalBytes + " bytes — từ chối (chống tar-bomb).");
            }
            validateChecksum(header, name);

            byte[] content = new byte[(int) size];
            readFully(in, content);
            int pad = BLOCK - ((int) size % BLOCK);
            if (pad < BLOCK) {
                skipFully(in, pad);
            }
            if (++count > maxEntries) {
                throw new IOException("Archive có quá nhiều entry (giới hạn " + maxEntries + ").");
            }
            totalBytes += size;
            entries.put(name, content);
        }
        return entries;
    }

    private void validateName(String name) throws IOException {
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || name.equals(".") || name.length() > 100) {
            throw new IOException("Entry không hợp lệ (phải là tên file phẳng): '" + name + "'");
        }
    }

    private static void validateChecksum(byte[] header, String name) throws IOException {
        long stored = parseOctal(header, 148);
        byte[] copy = header.clone();
        for (int i = 148; i < 156; i++) {
            copy[i] = ' ';
        }
        int sum = 0;
        for (byte b : copy) {
            sum += b & 0xFF;
        }
        if (stored != sum) {
            throw new IOException("Checksum tar không khớp cho entry '" + name
                    + "' — file có thể bị hỏng hoặc không phải snapshot của plugin.");
        }
    }

    /** Đọc giá trị octal (có thể có NUL/space padding); -1 nếu gặp ký tự lạ. */
    private static long parseOctal(byte[] buf, int off) {
        long value = 0;
        boolean seen = false;
        for (int i = off; i < off + 12; i++) {
            byte b = buf[i];
            if (b == 0 || b == ' ') {
                if (seen) {
                    break; // hết phần số
                }
                continue;
            }
            if (b < '0' || b > '7') {
                return -1;
            }
            seen = true;
            value = value * 8 + (b - '0');
        }
        return value;
    }

    private static String ascii(byte[] buf, int off, int len) {
        return new String(buf, off, len, StandardCharsets.US_ASCII).replace("\0", "");
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) {
                throw new IOException("EOF giữa chừng khi đọc tar");
            }
            read += n;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long skipped = 0;
        while (skipped < n) {
            long s = in.skip(n - skipped);
            if (s <= 0) {
                throw new IOException("Không skip được tar padding");
            }
            skipped += s;
        }
    }

    private static boolean allZero(byte[] buf) {
        for (byte b : buf) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
