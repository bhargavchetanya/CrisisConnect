package com.crisisconnect.exception;

/**
 * Login / permission problems. Carries the HTTP status the web API should answer with
 * (401 not logged in, 403 not allowed, 423 account locked, 429 too many attempts)
 * and a short machine-readable code the website can react to.
 */
public class AuthException extends CrisisConnectException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public AuthException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AuthException notLoggedIn() {
        return new AuthException(401, "NOT_LOGGED_IN", "Please log in as a coordinator to do this.");
    }

    public static AuthException forbidden(String message) {
        return new AuthException(403, "FORBIDDEN", message);
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
}
