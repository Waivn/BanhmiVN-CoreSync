package vn.banhmivn.coresync;

/**
 * Trạng thái khai báo của server trong plugin, được ánh xạ sang giá trị
 * status mà website BanhmiVN.fun chấp nhận (online|offline|maintenance|update).
 *
 * <ul>
 *   <li>ONLINE → online</li>
 *   <li>MAINTENANCE → maintenance</li>
 *   <li>CLOSED → offline</li>
 *   <li>UPCOMING_LAUNCH → update (server sắp mở)</li>
 * </ul>
 */
public enum ServerState {
    ONLINE("online"),
    MAINTENANCE("maintenance"),
    CLOSED("offline"),
    UPCOMING_LAUNCH("update");

    private final String webStatus;

    ServerState(String webStatus) {
        this.webStatus = webStatus;
    }

    public String webStatus() {
        return webStatus;
    }

    /** Case-insensitive parse of the plugin-side state name. */
    public static ServerState fromConfig(String raw) {
        if (raw == null) {
            return ONLINE;
        }
        for (ServerState s : values()) {
            if (s.name().equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        return ONLINE;
    }
}
