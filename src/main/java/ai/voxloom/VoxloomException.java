package ai.voxloom;

/** Base class for everything this SDK throws. */
public class VoxloomException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VoxloomException(String message) {
        super(message);
    }

    public VoxloomException(String message, Throwable cause) {
        super(message, cause);
    }
}
