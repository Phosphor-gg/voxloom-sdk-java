package ai.voxloom;

/** 429. Too much submitted too quickly. The details say how long the window has left; wait for it rather than retrying in a loop. */
public final class RateLimitedException extends ApiException {

    private static final long serialVersionUID = 1L;

    RateLimitedException(int statusCode, String error, String details, String body) {
        super(statusCode, error, details, body);
    }
}
