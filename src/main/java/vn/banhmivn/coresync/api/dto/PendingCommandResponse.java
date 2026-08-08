package vn.banhmivn.coresync.api.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Response của {@code GET /api/export/pending?server=<id>} (website) —
 * lệnh đang chờ mà admin yêu cầu từ web (vd {@code "exportaudit"} hoặc
 * {@code "importaudit"}). {@code command} = null khi không có lệnh nào.
 *
 * <p>{@code fileB64} chỉ có khi lệnh là {@code importaudit}: snapshot
 * {@code .tar.gz} (bản rõ) admin upload từ dashboard, base64-encoded —
 * plugin giải mã, ghi vào {@code exports/} rồi khôi phục.
 */
public class PendingCommandResponse {

    private String command;
    private String server;

    @SerializedName("file_b64")
    private String fileB64;

    @SerializedName("created_at")
    private String createdAt;

    /** HMAC-SHA256 của lệnh (chống giả mạo khi MC_API_KEY lộ); null khi web chưa cấu hình secret. */
    private String sig;

    /** Người yêu cầu lệnh trên dashboard (email admin) — ghi vào audit trail. */
    @SerializedName("requested_by")
    private String requestedBy;

    public PendingCommandResponse() {
        // Gson
    }

    public String getCommand() {
        return command;
    }

    public String getServer() {
        return server;
    }

    public String getFileB64() {
        return fileB64;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getSig() {
        return sig;
    }

    public String getRequestedBy() {
        return requestedBy;
    }
}
