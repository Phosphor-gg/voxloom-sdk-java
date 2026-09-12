package ai.voxloom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Where the media comes from.
 *
 * <p>The JSON tag is {@code kind}, not {@code type}. That is the single most
 * common mistake against this API, which is why the factory methods exist:
 * use them rather than building the object by hand.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Source {

    private final String kind;
    private final String url;
    private final String uploadId;

    /**
     * Deserialising constructor.
     *
     * <p>Needed as well as the factory methods: every {@code Job} carries a
     * source, so without a creator here Jackson cannot build a job at all and
     * every response fails to parse. The factories stay the way callers
     * construct one, because they are the ones that validate.
     */
    @JsonCreator
    Source(
            @JsonProperty("kind") String kind,
            @JsonProperty("url") String url,
            @JsonProperty("upload_id") String uploadId) {
        this.kind = kind;
        this.url = url;
        this.uploadId = uploadId;
    }

    /** A single YouTube video, by URL or bare id. */
    public static Source youtube(String url) {
        return new Source("youtube", requireUrl(url), null);
    }

    /** A YouTube playlist, expanded to at most 25 videos. */
    public static Source playlist(String url) {
        return new Source("youtube_playlist", requireUrl(url), null);
    }

    /** A YouTube channel's 25 most recent uploads. */
    public static Source channel(String url) {
        return new Source("youtube_channel", requireUrl(url), null);
    }

    /** Any other direct media URL: a podcast enclosure, an mp3, an mp4. */
    public static Source url(String url) {
        return new Source("url", requireUrl(url), null);
    }

    /** A file staged by {@link Voxloom#upload}. */
    public static Source upload(String uploadId) {
        return new Source("upload", null, Objects.requireNonNull(uploadId, "uploadId"));
    }

    private static String requireUrl(String url) {
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) {
            throw new IllegalArgumentException("the URL must not be blank");
        }
        return url;
    }

    @JsonProperty("kind")
    public String kind() {
        return kind;
    }

    @JsonProperty("url")
    public String url() {
        return url;
    }

    @JsonProperty("upload_id")
    public String uploadId() {
        return uploadId;
    }

    /** Whether this source expands into several jobs rather than one. */
    @JsonIgnore
    public boolean isBatch() {
        return "youtube_playlist".equals(kind) || "youtube_channel".equals(kind);
    }

    /** Something worth showing in a list. */
    @JsonIgnore
    public String display() {
        return url != null ? url : uploadId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Source)) {
            return false;
        }
        Source that = (Source) other;
        return Objects.equals(kind, that.kind)
                && Objects.equals(url, that.url)
                && Objects.equals(uploadId, that.uploadId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, url, uploadId);
    }

    @Override
    public String toString() {
        return "Source[" + kind + " " + display() + "]";
    }
}
