package vn.banhmivn.coresync.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writer TAR tối giản (định dạng ustar, chưa nén) — đủ để đóng gói vài file
 * text nhỏ thành một archive. Output tương thích mọi công cụ giải nén thông
 * dụng (7-Zip, WinRAR, tar, bsdtar). Không cần thư viện ngoài để tránh phình
 * kích thước plugin.
 *
 * <p>Chỉ hỗ trợ regular file (typeflag {@code '0'}), tên entry ≤ 100 ký tự —
 * đủ cho các file snapshot của plugin.
 */
final class TarWriter {

    private static final int BLOCK = 512;

    private final OutputStream out;

    TarWriter(OutputStream out) {
        this.out = out;
    }

    /** Ghi một entry file thường. */
    void writeEntry(String name, byte[] content, long mtimeSeconds) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 100) {
            throw new IOException("Tên entry tar không hợp lệ: " + name);
        }
        byte[] header = new byte[BLOCK];
        putAscii(header, 0, name, 100);              // name
        putAscii(header, 100, "0000644\0", 8);       // mode (rw-r--r--)
        putAscii(header, 108, "0000000\0", 8);       // uid
        putAscii(header, 116, "0000000\0", 8);       // gid
        putOctal(header, 124, content.length, 12);   // size (octal + NUL)
        putOctal(header, 136, mtimeSeconds, 12);     // mtime (octal + NUL)
        // chksum: để trống (8 spaces) rồi tính tổng sau
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = '0';                           // typeflag: regular file
        // linkname (157..256) rỗng
        putAscii(header, 257, "ustar\0", 6);         // magic
        putAscii(header, 263, "00", 2);              // version
        // uname (265), gname (297), devmajor (329), devminor (337), prefix (345) rỗng

        int checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        // Tối đa 512*255 = 130560 = 0o377000 → vừa khít 6 ký số octal + NUL + space
        putAscii(header, 148, String.format("%06o\0 ", checksum), 8);

        out.write(header);
        out.write(content);
        int pad = BLOCK - (content.length % BLOCK);
        if (pad < BLOCK) {
            out.write(new byte[pad]);
        }
    }

    /** Kết thúc archive bằng 2 block 0 (chuẩn tar). */
    void finish() throws IOException {
        out.write(new byte[BLOCK * 2]);
        out.flush();
    }

    private static void putAscii(byte[] buf, int off, String s, int len) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, buf, off, Math.min(b.length, len));
    }

    private static void putOctal(byte[] buf, int off, long value, int len) {
        // len-1 ký số octal (đệm 0) + NUL
        String octal = Long.toOctalString(value);
        StringBuilder sb = new StringBuilder(len);
        for (int i = octal.length(); i < len - 1; i++) {
            sb.append('0');
        }
        sb.append(octal).append('\0');
        byte[] b = sb.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, buf, off, len);
    }
}
