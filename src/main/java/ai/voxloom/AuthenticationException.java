package ai.voxloom;

/** 401. The key is missing, invalid or revoked. */
public final class AuthenticationException extends ApiException {

    private static final long serialVersionUID = 1L;

    AuthenticationException(int statusCode, String error, String details, String body) {
        super(statusCode, error, details, body);
    }
}
