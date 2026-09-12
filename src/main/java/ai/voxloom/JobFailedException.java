package ai.voxloom;

/**
 * A job reached {@code failed}, thrown by the waiting helpers.
 *
 * <p>Carries the job so the caller can read {@code failure.retryable} and
 * decide whether a retry is worth attempting.
 */
public final class JobFailedException extends VoxloomException {

    private static final long serialVersionUID = 1L;

    private final transient Models.Job job;

    public JobFailedException(Models.Job job) {
        super("voxloom: job " + job.id + " failed"
                + (job.failure == null ? "" : ": " + job.failure.message));
        this.job = job;
    }

    public Models.Job job() {
        return job;
    }

    /** Whether the identical job could succeed on a retry. */
    public boolean isRetryable() {
        return job.failure != null && job.failure.retryable;
    }

    /** Why it failed. */
    public FailureKind kind() {
        return job.failure == null ? FailureKind.INTERNAL : job.failure.kind;
    }

    /** Whether this failure was refunded. */
    public boolean refundsCredits() {
        return kind().refundsCredits();
    }
}
