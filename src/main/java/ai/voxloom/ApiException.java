package ai.voxloom;

/**
 * A non-2xx response.
 *
 * <p>The subclasses exist so callers can {@code catch} what to do about a
 * failure rather than inspecting a status code or parsing a message. In
 * particular {@link OutOfCreditsException} and {@link PlanTooLowException} are
 * separate because one means "buy more minutes and the same request works" and
 * the other means "it never will".
 */
public class ApiException extends VoxloomException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String error;
    private final String details;
    private final String body;

    public ApiException(int statusCode, String error, String details, String body) {
        super(buildMessage(statusCode, error, details));
        this.statusCode = statusCode;
        this.error = error;
        this.details = details;
        this.body = body;
    }

    private static String buildMessage(int statusCode, String error, String details) {
        if (details != null && !details.isEmpty()) {
            return "voxloom: " + statusCode + " " + error + ": " + details;
        }
        return "voxloom: " + statusCode + " " + error;
    }

    public int statusCode() {
        return statusCode;
    }

    /** The status name the API returned, e.g. "Payment Required". */
    public String error() {
        return error;
    }

    /** The human-readable explanation, safe to show a user. */
    public String details() {
        return details;
    }

    /** The raw response, for anything not modelled above. */
    public String body() {
        return body;
    }

    /**
     * Whether the identical request could succeed if retried later.
     *
     * <p>True for rate limits and server errors. False for anything the
     * caller has to change first.
     */
    public boolean isRetryable() {
        return statusCode == 429 || statusCode >= 500;
    }

    /** Build the most specific subclass for a status. */
    static ApiException of(int statusCode, String error, String details, String body) {
        switch (statusCode) {
            case 400:
                return new ValidationException(statusCode, error, details, body);
            case 401:
                return new AuthenticationException(statusCode, error, details, body);
            case 402:
                return new OutOfCreditsException(statusCode, error, details, body);
            case 403:
                return new PlanTooLowException(statusCode, error, details, body);
            case 404:
                return new NotFoundException(statusCode, error, details, body);
            case 429:
                return new RateLimitedException(statusCode, error, details, body);
            default:
                if (statusCode >= 500) {
                    return new ServerException(statusCode, error, details, body);
                }
                return new ApiException(statusCode, error, details, body);
        }
    }
}
