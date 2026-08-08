package vn.banhmivn.coresync.api.dto;

import com.google.gson.annotations.SerializedName;

/** Kết quả {@code POST /api/codes/sync}. */
public class CodeSyncResponse {

    private boolean success;
    private String message;

    @SerializedName("order_id")
    private Long orderId;

    private String code;
    private String status;

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getCode() {
        return code;
    }

    public String getStatus() {
        return status;
    }
}
