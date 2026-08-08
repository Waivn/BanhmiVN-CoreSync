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
}
