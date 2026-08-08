package vn.banhmivn.coresync.api.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Body của {@code POST /api/codes/sync} — đăng ký một Giftcode do plugin
 * tự sinh vào database website (tạo ShopOrder với redemption_code để mã
 * có thể redeem qua {@code /api/codes/redeem} như mã mua trên web).
 */
public class CodeSyncRequest {

    private final String code;

    @SerializedName("player_name")
    private final String playerName;

    private final List<CodeItem> items;

    public CodeSyncRequest(String code, String playerName, List<CodeItem> items) {
        this.code = code;
        this.playerName = playerName;
        this.items = items == null ? List.of() : items;
    }

    public String getCode() {
        return code;
    }

    public String getPlayerName() {
        return playerName;
    }

    public List<CodeItem> getItems() {
        return items;
    }
}
