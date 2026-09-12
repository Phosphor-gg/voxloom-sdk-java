package ai.voxloom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A transcription model. Costs per second of audio are 1x, 2x and 4x. */
public enum Model {
    /**
     * Picks the best model the plan includes, weighing the recording's length
     * against the remaining balance.
     */
    AUTO("auto", 2),
    /** Fastest and cheapest. */
    THREAD("thread", 1),
    /** The default, and the rate every figure is quoted at. */
    WEAVE("weave", 2),
    /** Most accurate, for crosstalk and strong accents. */
    TAPESTRY("tapestry", 4);

    private final String wire;
    private final int multiplier;

    Model(String wire, int multiplier) {
        this.wire = wire;
        this.multiplier = multiplier;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** What a second of audio costs on this model. */
    public int multiplier() {
        return multiplier;
    }

    @JsonCreator
    public static Model fromWire(String value) {
        for (Model model : values()) {
            if (model.wire.equalsIgnoreCase(value)) {
                return model;
            }
        }
        // A newer server naming a model this client does not know must not
        // break deserialisation mid-pipeline.
        return AUTO;
    }
}
