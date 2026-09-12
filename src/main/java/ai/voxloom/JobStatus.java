package ai.voxloom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Where a job is. */
public enum JobStatus {
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    TRANSCRIBING("transcribing"),
    /** Diarisation, cleanup, summarisation, translation. */
    ENRICHING("enriching"),
    /** Writing the requested export formats. */
    RENDERING("rendering"),
    COMPLETE("complete"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wire;

    JobStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** Whether nothing further will happen to a job in this state. */
    public boolean isTerminal() {
        return this == COMPLETE || this == FAILED || this == CANCELLED;
    }

    /** Whether a job is still in progress. */
    public boolean isRunning() {
        return !isTerminal();
    }

    @JsonCreator
    public static JobStatus fromWire(String value) {
        for (JobStatus status : values()) {
            if (status.wire.equalsIgnoreCase(value)) {
                return status;
            }
        }
        // A newer server adding a status must not break an older client in
        // the middle of somebody's pipeline. Queued is the safe reading: it
        // means "not finished", which is true of anything unrecognised.
        return QUEUED;
    }
}
