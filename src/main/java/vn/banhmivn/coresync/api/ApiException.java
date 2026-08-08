package vn.banhmivn.coresync.api;

/** Exception khi backend trả lỗi (HTTP 4xx/5xx) hoặc mạng lỗi. */
public class ApiException extends RuntimeException {

    private final int statusCode;
    private final String detail;

    public ApiException(int statusCode, String detail, Throwable cause) {
        super("API error " + statusCode + ": " + detail, cause);
        this.statusCode = statusCode;
        this.detail = detail;
    }

    public ApiException(int statusCode, String detail) {
        this(statusCode, detail, null);
    }

    public ApiException(Throwable cause) {
        super(cause);
        this.statusCode = 0;
        this.detail = cause.getMessage();
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getDetail() {
        return detail;
    }

    /** True nếu đây là lỗi mạng/timeout (không phải response HTTP). */
    public boolean isNetworkError() {
        return statusCode == 0;
    }

    /** Website dùng 409 = mã đã dùng, 410 = đơn bị từ chối. */
    public boolean isAlreadyUsed() {
        return statusCode == 409 || statusCode == 410;
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }
}
