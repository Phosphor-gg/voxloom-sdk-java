package ai.voxloom;

import java.time.Duration;

/**
 * A job did not finish inside the caller's timeout.
 *
 * <p>The job is still running and nothing extra has been charged, so it is
 * carried here for the caller to come back to.
 */
public final class JobTimeoutException extends VoxloomException {

    private static final long serialVersionUID = 1L;

    private final transient Models.Job job;
    private final Duration timeout;

    public JobTimeoutException(Models.Job job, Duration timeout) {
        super("voxloom: job " + job.id + " was still " + job.status.wire()
                + " after " + timeout.toSeconds() + "s; it is still running, "
                + "poll it again with its id");
        this.job = job;
        this.timeout = timeout;
    }

    public Models.Job job() {
        return job;
    }

    public Duration timeout() {
        return timeout;
    }
}
