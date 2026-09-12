package ai.voxloom;

/** 400. A field is wrong, or no key was sent. */
public final class ValidationException extends ApiException {

    private static final long serialVersionUID = 1L;

    ValidationException(int statusCode, String error, String details, String body) {
        super(statusCode, error, details, body);
    }
}
