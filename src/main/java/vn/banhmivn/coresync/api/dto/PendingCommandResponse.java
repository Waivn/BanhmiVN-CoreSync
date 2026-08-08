package vn.banhmivn.coresync.api.dto;

/**
 * Response của {@code GET /api/export/pending?server=<id>} (website) —
 * lệnh đang chờ mà admin yêu cầu từ web (vd {@code "exportaudit"}).
 * {@code command} = null khi không có lệnh nào.
 */
public class PendingCommandResponse {

    private String command;
    private String server;

    public PendingCommandResponse() {
        // Gson
    }

    public String getCommand() {
        return command;
    }

    public String getServer() {
        return server;
    }
}
