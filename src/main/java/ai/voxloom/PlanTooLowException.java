package ai.voxloom;

/** 403. The plan does not include this; more minutes will not help. */
public final class PlanTooLowException extends ApiException {

    private static final long serialVersionUID = 1L;

    PlanTooLowException(int statusCode, String error, String details, String body) {
        super(statusCode, error, details, body);
    }
}
