package io.github.melin.lakehouse.rest.engine.auth;

/**
 * 换票失败。
 *
 * <p>带 {@code errorCode}（RFC 6749 §5.2 的 {@code error} 取值）而不只是一个消息，
 * 是因为有一个分支必须按取值判断：服务端不认识 {@code token-exchange} 时回
 * {@code unsupported_grant_type}，此时若配置了凭据就改走 {@code client_credentials}
 * ——这是「只升引擎、没升服务端」那个升级窗口里唯一的活路（见
 * {@code docs/engine-token-refresh-design.md} §6）。按消息文本判断会在服务端换一句话时静默失效。
 */
public class TokenRefreshException extends RuntimeException {

    /** 服务端给的 {@code error}；连不上或响应不是 OAuth 错误体时为 {@code null}。 */
    private final String errorCode;

    /** HTTP 状态码；连不上时为 0。 */
    private final int status;

    public TokenRefreshException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public TokenRefreshException(int status, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }

    public int status() {
        return status;
    }

    /** 服务端不认识我们发的 grant——典型的「服务端还没升级」。 */
    public boolean isUnsupportedGrant() {
        return "unsupported_grant_type".equals(errorCode);
    }
}
