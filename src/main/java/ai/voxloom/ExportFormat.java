package ai.voxloom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A format a transcript can be rendered in. All are free on every plan. */
public enum ExportFormat {
    TXT("txt"),
    SRT("srt"),
    VTT("vtt"),
    DOCX("docx"),
    PDF("pdf"),
    MD("md"),
    JSON("json"),
    CSV("csv"),
    TSV("tsv"),
    CHAPTERS("chapters");

    private final String wire;

    ExportFormat(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ExportFormat fromWire(String value) {
        for (ExportFormat format : values()) {
            if (format.wire.equalsIgnoreCase(value)) {
                return format;
            }
        }
        throw new IllegalArgumentException("unknown export format: " + value);
    }
}
