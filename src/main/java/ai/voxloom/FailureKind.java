package ai.voxloom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Why a job failed. */
public enum FailureKind {
    /** Private, deleted, members-only or region-locked. */
    MEDIA_UNAVAILABLE("media_unavailable"),
    /** Longer than the twelve hour limit. */
    MEDIA_TOO_LONG("media_too_long"),
    /** The URL did not resolve to readable media. */
    UNSUPPORTED_SOURCE("unsupported_source"),
    /** No speech detected in the audio at all. */
    NO_SPEECH("no_speech"),
    /** The balance ran out part-way through. The one unrefunded failure. */
    INSUFFICIENT_CREDITS("insufficient_credits"),
    TRANSCRIPTION_FAILED("transcription_failed"),
    TIMEOUT("timeout"),
    INTERNAL("internal"),
    /** A kind this client does not know yet. */
    UNKNOWN("unknown");

    private final String wire;

    FailureKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * Whether a failure of this kind is refunded.
     *
     * <p>Everything is, except running out of credits part-way through, which
     * keeps the work already performed charged.
     */
    public boolean refundsCredits() {
        return this != INSUFFICIENT_CREDITS;
    }

    @JsonCreator
    public static FailureKind fromWire(String value) {
        for (FailureKind kind : values()) {
            if (kind.wire.equalsIgnoreCase(value)) {
                return kind;
            }
        }
        return UNKNOWN;
    }
}
