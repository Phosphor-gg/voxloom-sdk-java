package ai.voxloom;

/** 404. No such job, or it belongs to another account. The API answers the same way for both on purpose: a 403 would confirm the job exists. */
public final class NotFoundException extends ApiException {

    private static final long serialVersionUID = 1L;

    NotFoundException(int statusCode, String error, String details, String body) {
        super(statusCode, error, details, body);
    }
}
