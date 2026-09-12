package ai.voxloom;

/** 402. Buying more minutes makes this exact request work. */
public final class OutOfCreditsException extends ApiException {

    private static final long serialVersionUID = 1L;

    OutOfCreditsException(int statusCode, String error, String details, String body) {
        super(statusCode, error, details, body);
    }
}
