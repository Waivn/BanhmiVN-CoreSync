package vn.banhmivn.coresync.api.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Body của {@code POST /api/codes/redeem} — khớp schema
 * {@code CodeRedeemRequest} bên website (app/schemas.py).
 */
public class CodeRedeemRequest {

    private final String code;

    @SerializedName("ign")
    private final String ign;

    @SerializedName("player_name")
    private final String playerName;

    public CodeRedeemRequest(String code, String playerName) {
        this.code = code;
        this.ign = playerName;   // website hỗ trợ cả hai alias
        this.playerName = playerName;
    }

    public String getCode() {
        return code;
    }

    public String getIgn() {
        return ign;
    }

    public String getPlayerName() {
        return playerName;
    }
}
