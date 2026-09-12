package ai.voxloom;

/** 5xx. Ours, or a dependency's. */
public final class ServerException extends ApiException {

    private static final long serialVersionUID = 1L;

    ServerException(int statusCode, String error, String details, String body) {
        super(statusCode, error, details, body);
    }
}
