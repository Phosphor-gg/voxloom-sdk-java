package ai.voxloom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The transcription model.
 *
 * <p>There is one. {@code AUTO}, {@code THREAD} and {@code TAPESTRY} are kept
 * as deprecated aliases so code written against the retired tiers still
 * compiles; all four constants serialise to {@code "weave"} and cost the same,
 * because a credit is a second of audio whichever name you use.
 */
public enum Model {
    /** Punctuation, casing, speaker labels and 101 languages. */
    WEAVE,
    /** @deprecated There is one model. Use {@link #WEAVE}. */
    @Deprecated
    AUTO,
    /** @deprecated There is one model. Use {@link #WEAVE}. */
    @Deprecated
    THREAD,
    /** @deprecated There is one model. Use {@link #WEAVE}. */
    @Deprecated
    TAPESTRY;

    @JsonValue
    public String wire() {
        return "weave";
    }

    /**
     * What a second of audio costs on this model.
     *
     * <p>Always 1. It returned 1, 2 or 4 per tier; kept so code that
     * multiplies by it keeps compiling and keeps being right.
     */
    public int multiplier() {
        return (int) Models.CREDITS_PER_MEDIA_SECOND;
    }

    /**
     * Parse a model name off the wire.
     *
     * <p>Every name resolves to {@link #WEAVE}, including the retired tiers
     * and anything this client has never heard of: a newer server naming a
     * model must not break deserialisation mid-pipeline.
     */
    @JsonCreator
    public static Model fromWire(String value) {
        return WEAVE;
    }
}
