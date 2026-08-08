package vn.banhmivn.coresync.alert;

import java.util.List;

/**
 * Cấu hình SMTP để gửi email cảnh báo cho staff (tuỳ chọn — tắt nếu chưa cấu hình).
 *
 * @param enabled  bật/tắt kênh email
 * @param host     SMTP host (vd smtp.gmail.com)
 * @param port     cổng SMTP (465 khi dùng SSL, 587 khi STARTTLS)
 * @param username tài khoản đăng nhập SMTP
 * @param password mật khẩu / app-password
 * @param ssl      true → kết nối SSL/TLS trực tiếp (cổng 465); false → STARTTLS (587)
 * @param from     địa chỉ người gửi
 * @param to       danh sách người nhận (staff)
 */
public record EmailSettings(
        boolean enabled,
        String host,
        int port,
        String username,
        String password,
        boolean ssl,
        String from,
        List<String> to) {

    /** Cấu hình "tắt" mặc định — gọi khi thiếu section hoặc chưa đủ thông tin. */
    public static EmailSettings disabled() {
        return new EmailSettings(false, "", 587, "", "", false, "", List.of());
    }
}
