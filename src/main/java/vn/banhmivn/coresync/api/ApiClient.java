package vn.banhmivn.coresync.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import vn.banhmivn.coresync.api.dto.CodeRedeemRequest;
import vn.banhmivn.coresync.api.dto.CodeRedeemResponse;
import vn.banhmivn.coresync.api.dto.CodeSyncRequest;
import vn.banhmivn.coresync.api.dto.CodeSyncResponse;
import vn.banhmivn.coresync.api.dto.ServerStatusPayload;
import vn.banhmivn.coresync.export.SnapshotCipher;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Gọi API BanhmiVN.fun hoàn toàn bất đồng bộ ({@link HttpClient#sendAsync}).
 * Không bao giờ chặn main thread — mọi result được trả về qua CompletableFuture
 * và callback chuyển sang main thread bằng Bukkit scheduler ở tầng trên.
 *
 * <p>Auth: header {@code X-API-Key} (khớp {@code MC_API_KEY} trên website).
 */
public class ApiClient {

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String apiKeyHeader;
    private final Duration timeout;
    private final Gson gson = new Gson();

    public ApiClient(String baseUrl, String apiKey, String apiKeyHeader, int timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.apiKeyHeader = (apiKeyHeader == null || apiKeyHeader.isBlank()) ? "X-API-Key" : apiKeyHeader;
        this.timeout = Duration.ofSeconds(Math.max(3, timeoutSeconds));
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /** Redeem một Giftcode. */
    public CompletableFuture<CodeRedeemResponse> redeemCode(String code, String playerName) {
        CodeRedeemRequest body = new CodeRedeemRequest(code, playerName);
        return post("/api/codes/redeem", body, CodeRedeemResponse.class);
    }

    /** Đăng ký Giftcode do plugin tự sinh lên website DB. */
    public CompletableFuture<CodeSyncResponse> syncCode(CodeSyncRequest request) {
        return post("/api/codes/sync", request, CodeSyncResponse.class);
    }

    /** Đẩy telemetry server status (heartbeat). */
    public CompletableFuture<ServerStatusPayload> pushStatus(ServerStatusPayload payload) {
        return post("/api/server/status", payload, ServerStatusPayload.class);
    }

    /**
     * Đẩy snapshot audit (.tar.gz) lên {@code POST /api/export} cho staff tải về
     * (multipart/form-data: field {@code server} + file {@code file}).
     *
     * <p>Ghi chú: đọc file + mã hoá (vài chục ms với snapshot vài MB) chạy ngay
     * trên thread gọi (main) trước khi sendAsync — phần HTTP mới là async.
     *
     * @param cipher mã hoá AES-256-GCM nội dung trước khi gửi (at-rest trên website);
     *              {@code null} → gửi bản rõ (chỉ khi admin cố tình không cấu hình key).
     */
    public CompletableFuture<Void> uploadSnapshot(File snapshot, String serverId, SnapshotCipher cipher) {
        if (!isConfigured()) {
            return CompletableFuture.failedFuture(
                    new ApiException(0, "MC_API_KEY chưa được cấu hình trên plugin (api.key rỗng)"));
        }
        try {
            byte[] content = Files.readAllBytes(snapshot.toPath());
            byte[] uploaded = cipher == null ? content : cipher.encrypt(content);
            MultipartBody.Body body = MultipartBody.build(List.of(
                    new MultipartBody.Part("server", null, null,
                            serverId.getBytes(StandardCharsets.UTF_8)),
                    new MultipartBody.Part("file", snapshot.getName(), "application/gzip", uploaded)));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/export"))
                    .timeout(timeout)
                    .header(apiKeyHeader, apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes()))
                    .build();

            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        handle(response, Object.class); // 2xx → ok; khác → ApiException
                        return null;
                    });
        } catch (IOException ex) {
            return CompletableFuture.failedFuture(
                    new ApiException(0, "Không đọc được snapshot: " + ex.getMessage()));
        } catch (RuntimeException ex) {
            // Mã hoá/Multipart lỗi → trả failedFuture thay vì throw sync (giữ hợp đồng async).
            return CompletableFuture.failedFuture(
                    new ApiException(0, "Chuẩn bị snapshot thất bại: " + ex.getMessage()));
        }
    }

    private <T> CompletableFuture<T> post(String path, Object body, Class<T> responseType) {
        if (!isConfigured()) {
            return CompletableFuture.failedFuture(
                    new ApiException(0, "MC_API_KEY chưa được cấu hình trên plugin (api.key rỗng)"));
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(apiKeyHeader, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> handle(response, responseType));
    }

    private <T> T handle(HttpResponse<String> response, Class<T> responseType) {
        String bodyText = response.body() == null ? "" : response.body().trim();
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return gson.fromJson(bodyText, responseType);
        }
        // Website trả lỗi dạng {"detail": "..."} (FastAPI).
        String detail = bodyText;
        try {
            JsonObject obj = JsonParser.parseString(bodyText).getAsJsonObject();
            if (obj.has("detail")) {
                detail = obj.get("detail").getAsString();
            }
        } catch (RuntimeException ignored) {
            // không parse được JSON → giữ nguyên body
        }
        throw new ApiException(status, detail);
    }
}
