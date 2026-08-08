package vn.banhmivn.coresync.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builder body {@code multipart/form-data} thuần Java — {@link java.net.http.HttpClient}
 * không có sẵn multipart. Dùng để đẩy snapshot audit lên {@code POST /api/export}
 * (field {@code server} + file {@code file}).
 */
public final class MultipartBody {

    /** Một phần trong body multipart. filename = null → field thường. */
    public record Part(String name, String filename, String contentType, byte[] content) {
    }

    /** Kết quả: boundary (để set Content-Type) + toàn bộ bytes body. */
    public record Body(String boundary, byte[] bytes) {
    }

    private MultipartBody() {
    }

    public static Body build(List<Part> parts) {
        String boundary = "----BanhmiVN" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        try {
            for (Part part : parts) {
                writeLine(out, "--" + boundary);
                if (part.filename() == null) {
                    writeLine(out, "Content-Disposition: form-data; name=\"" + part.name() + "\"");
                } else {
                    writeLine(out, "Content-Disposition: form-data; name=\"" + part.name()
                            + "\"; filename=\"" + part.filename() + "\"");
                    writeLine(out, "Content-Type: " + part.contentType());
                }
                writeLine(out, "");
                out.write(part.content());
                writeLine(out, "");
            }
            writeLine(out, "--" + boundary + "--");
        } catch (IOException ex) {
            // ByteArrayOutputStream không bao giờ throw IOException thật
            throw new UncheckedIOException(ex);
        }
        return new Body(boundary, out.toByteArray());
    }

    private static void writeLine(ByteArrayOutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
    }
}
