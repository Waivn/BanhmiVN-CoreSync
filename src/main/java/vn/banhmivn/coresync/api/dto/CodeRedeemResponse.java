package vn.banhmivn.coresync.api.dto;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** Kết quả {@code POST /api/codes/redeem} — server dùng {@code items} để trao đồ. */
public class CodeRedeemResponse {

    private boolean success;
    private String message;
    private String status;

    @SerializedName("order_code")
    private String orderCode;

    @SerializedName("user_id")
    private Long userId;

    @SerializedName("user_ign")
    private String userIgn;

    @SerializedName("redeemed_by")
    private String redeemedBy;

    private double total;

    private List<CodeItem> items = new ArrayList<>();

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getStatus() {
        return status;
    }

    public String getOrderCode() {
        return orderCode;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUserIgn() {
        return userIgn;
    }

    public String getRedeemedBy() {
        return redeemedBy;
    }

    public double getTotal() {
        return total;
    }

    public List<CodeItem> getItems() {
        return items == null ? List.of() : items;
    }
}
