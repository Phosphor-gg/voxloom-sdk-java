package ai.voxloom;

/** The request never got a response: DNS, a refused connection, or a timeout. */
public final class TransportException extends VoxloomException {

    private static final long serialVersionUID = 1L;

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
