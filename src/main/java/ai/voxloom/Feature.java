package ai.voxloom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * An optional extra.
 *
 * <p>Each adds a per-second surcharge except {@link #CHAPTERS} alongside a
 * summary and {@link #WORD_TIMESTAMPS}, both of which are free.
 */
public enum Feature {
    DIARIZATION("diarization"),
    CLEANUP("cleanup"),
    SUMMARY("summary"),
    CHAPTERS("chapters"),
    TRANSLATION("translation"),
    WORD_TIMESTAMPS("word_timestamps");

    private final String wire;

    Feature(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static Feature fromWire(String value) {
        for (Feature feature : values()) {
            if (feature.wire.equalsIgnoreCase(value)) {
                return feature;
            }
        }
        throw new IllegalArgumentException("unknown feature: " + value);
    }
}
