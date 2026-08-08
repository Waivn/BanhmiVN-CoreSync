package vn.banhmivn.coresync.alert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Kênh gửi cảnh báo cho staff: Discord webhook (bất đồng bộ qua
 * {@link HttpClient#sendAsync}) và/hoặc email SMTP (chạy trên executor riêng).
 *
 * <p>Không bao giờ chặn main thread — cả hai kênh đều chạy ngoài luồng chính;
 * mọi lỗi (URL sai, SMTP fail, mạng lỗi) chỉ ghi log warning, không làm gián
 * đoạn game. URL webhook được kiểm tra ngay lúc khởi tạo: nếu sai → kênh Discord
 * bị tắt thay vì ném ngoại lệ trên main thread lúc gửi.
 */
public class AlertNotifier implements SuspicionDetector.Sink {

    private static final int DISCORD_COLOR_RED = 0xE74C3C;

    private final Plugin plugin;
    private final String serverName;
    private final String discordWebhookUrl;
    private final EmailSettings email;
    private final HttpClient http;

    /** Tạo lazy — chỉ dựng executor khi thực sự gửi email lần đầu. */
    private volatile ExecutorService mailExecutor;

    public AlertNotifier(Plugin plugin, String serverName, String discordWebhookUrl, EmailSettings email) {
        this.plugin = plugin;
        this.serverName = serverName;
        this.email = email == null ? EmailSettings.disabled() : email;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String raw = discordWebhookUrl == null ? "" : discordWebhookUrl.trim();
        if (!raw.isBlank() && !isValidUri(raw)) {
            plugin.getLogger().warning("Discord webhook URL không hợp lệ — tắt kênh Discord cảnh báo: " + raw);
            raw = "";
        }
        this.discordWebhookUrl = raw;
    }

    /** Đóng executor gửi mail — gọi trong onDisable/reloadAll (an toàn gọi nhiều lần). */
    public void shutdown() {
        ExecutorService exec = mailExecutor;
        if (exec == null) {
            return;
        }
        mailExecutor = null;
        exec.shutdownNow();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Không dừng kịp executor gửi mail cảnh báo.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void fireAlert(String title, String message) {
        String full = "🚨 " + title + " [" + serverName + "]\n" + message;
        plugin.getLogger().warning("[STAFF-ALERT] " + full.replace('\n', ' '));

        if (!discordWebhookUrl.isBlank()) {
            sendDiscord(title, message);
        }
        if (email.enabled()) {
            mailExecutor().execute(() -> sendEmail(title, message));
        }
    }

    // ── Discord webhook ─────────────────────────────────────

    private static boolean isValidUri(String url) {
        try {
            URI.create(url);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * Gửi embed cảnh báo lên Discord. URL đã được kiểm tra lúc khởi tạo, nhưng
     * vẫn bọc try/catch đề phòng mọi lỗi khác — KHÔNG BAO GIỜ ném lên main thread.
     */
    private void sendDiscord(String title, String message) {
        try {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", "🚨 " + title + " — " + serverName);
            embed.addProperty("description", message);
            embed.addProperty("color", DISCORD_COLOR_RED);
            embed.addProperty("timestamp", Instant.now().toString());
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            JsonObject payload = new JsonObject();
            payload.add("embeds", embeds);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(discordWebhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();

            http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "Không gửi được cảnh báo Discord: " + err.getMessage(), err);
                        } else if (resp.statusCode() >= 300) {
                            plugin.getLogger().warning(
                                    "Discord webhook trả về " + resp.statusCode() + ": " + resp.body());
                        }
                    });
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Lỗi khi dựng request Discord", ex);
        }
    }

    // ── SMTP email ──────────────────────────────────────────

    private ExecutorService mailExecutor() {
        ExecutorService exec = mailExecutor;
        if (exec == null) {
            synchronized (this) {
                exec = mailExecutor;
                if (exec == null) {
                    exec = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "bmvn-alert-mail");
                        t.setDaemon(true);
                        return t;
                    });
                    mailExecutor = exec;
                }
            }
        }
        return exec;
    }

    private void sendEmail(String title, String message) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", email.host());
            props.put("mail.smtp.port", String.valueOf(email.port()));
            props.put("mail.smtp.auth", "true");
            if (email.ssl()) {
                props.put("mail.smtp.socketFactory.port", String.valueOf(email.port()));
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.socketFactory.fallback", "false");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(email.username(), email.password());
                }
            });
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(email.from()));
            for (String to : email.to()) {
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
            }
            msg.setSubject("[BanhmiVN " + serverName + "] " + title);
            msg.setText("🚨 " + title + "\n\n" + message
                    + "\n\n— BanhmiVN-CoreSync (server " + serverName + ")");
            Transport.send(msg);
            plugin.getLogger().info("Đã gửi email cảnh báo tới " + email.to());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Không gửi được email cảnh báo", ex);
        }
    }

    /** Chỉ để test: kênh Discord có được cấu hình không. */
    public boolean discordConfigured() {
        return !discordWebhookUrl.isBlank();
    }

    /** Chỉ để test: kênh email có được cấu hình không. */
    public boolean emailConfigured() {
        return email.enabled();
    }
}
