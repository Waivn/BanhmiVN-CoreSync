package vn.banhmivn.coresync.api.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Body của {@code POST /api/server/status} — khớp {@code ServerStatusUpdateRequest}
 * bên website. Chỉ các field khác null mới được website merge vào config,
 * nên status/message/reopen_at do admin đặt trên web vẫn được giữ nguyên.
 */
public class ServerStatusPayload {

    private final String status;          // online|offline|maintenance|update
    private final String message;

    @SerializedName("player_count")
    private final Integer playerCount;

    @SerializedName("max_players")
    private final Integer maxPlayers;

    private final Double tps;

    private final Integer ping;

    /** Bộ nhớ đang dùng (MB) — field phụ, website bỏ qua nếu chưa hỗ trợ. */
    @SerializedName("memory_mb")
    private final Long memoryMb;

    public ServerStatusPayload(String status, String message, Integer playerCount,
                               Integer maxPlayers, Double tps, Integer ping, Long memoryMb) {
        this.status = status;
        this.message = message;
        this.playerCount = playerCount;
        this.maxPlayers = maxPlayers;
        this.tps = tps;
        this.ping = ping;
        this.memoryMb = memoryMb;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Integer getPlayerCount() {
        return playerCount;
    }

    public Integer getMaxPlayers() {
        return maxPlayers;
    }

    public Double getTps() {
        return tps;
    }

    public Integer getPing() {
        return ping;
    }

    public Long getMemoryMb() {
        return memoryMb;
    }
}
