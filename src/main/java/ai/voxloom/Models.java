package ai.voxloom;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * The response types.
 *
 * <p>Every one is annotated {@code ignoreUnknown}: a server that adds a field
 * must not break an older client, and an SDK you have to upgrade before you
 * can ignore a new field is an SDK that gets in the way.
 */
public final class Models {

    private Models() {}

    /** One second of audio at the base rate. */
    public static final long CREDITS_PER_MEDIA_SECOND = 1;

    /**
     * Credits in one standard minute.
     *
     * <p>Standard minutes are minutes on {@link Model#WEAVE}, the rate every
     * customer-facing figure is quoted at. Dividing credits by 60 instead
     * states a balance at Thread's rate and overstates it twofold.
     */
    public static final long CREDITS_PER_STANDARD_MINUTE = 120;

    /** Credits as the minutes of audio they buy at the standard rate. */
    public static double creditsToStandardMinutes(long credits) {
        return Math.max(0, credits) / (double) CREDITS_PER_STANDARD_MINUTE;
    }

    /** Standard minutes as credits. */
    public static long standardMinutesToCredits(double minutes) {
        return (long) (Math.max(0, minutes) * CREDITS_PER_STANDARD_MINUTE);
    }

    /** Seconds as "1h 24m" or "3m 20s", matching what the product shows. */
    public static String formatDuration(double secs) {
        long total = (long) Math.max(0, secs);
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return (total % 60) + "s";
    }

    /** Milliseconds as "1:02:05", the format YouTube turns into links. */
    public static String formatTimestamp(long ms) {
        long total = Math.max(0, ms / 1000);
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    /** Credits as the media time they buy at the standard rate. */
    public static String formatCredits(long credits) {
        return formatDuration(creditsToStandardMinutes(credits) * 60.0);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class MediaMetadata {
        @JsonProperty("title") public String title;
        @JsonProperty("channel") public String channel;
        @JsonProperty("channel_url") public String channelUrl;
        @JsonProperty("thumbnail_url") public String thumbnailUrl;
        @JsonProperty("published_at") public String publishedAt;
        @JsonProperty("duration_secs") public double durationSecs;
        @JsonProperty("video_id") public String videoId;
        @JsonProperty("source_url") public String sourceUrl;
    }

    /** One word with its timing. Present when word timestamps were asked for. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Word {
        @JsonProperty("start_ms") public long startMs;
        @JsonProperty("end_ms") public long endMs;
        @JsonProperty("text") public String text;
        @JsonProperty("confidence") public Double confidence;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Segment {
        @JsonProperty("start_ms") public long startMs;
        @JsonProperty("end_ms") public long endMs;
        @JsonProperty("text") public String text;
        /** Speaker id from diarisation, e.g. "speaker_0". */
        @JsonProperty("speaker") public String speaker;
        @JsonProperty("confidence") public Double confidence;
        @JsonProperty("words") public List<Word> words = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Speaker {
        @JsonProperty("id") public String id;
        /**
         * Defaults to "Speaker 1", "Speaker 2" and so on, numbered by first
         * appearance, until renamed.
         */
        @JsonProperty("label") public String label;
        @JsonProperty("speaking_secs") public double speakingSecs;
        @JsonProperty("segment_count") public int segmentCount;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Chapter {
        @JsonProperty("start_ms") public long startMs;
        @JsonProperty("title") public String title;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class PullQuote {
        /** Verbatim. Check it against the timestamp before publishing. */
        @JsonProperty("text") public String text;
        @JsonProperty("start_ms") public long startMs;
        @JsonProperty("speaker") public String speaker;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Summary {
        @JsonProperty("abstract_text") public String abstractText;
        @JsonProperty("key_points") public List<String> keyPoints = new ArrayList<>();
        @JsonProperty("pull_quotes") public List<PullQuote> pullQuotes = new ArrayList<>();
        @JsonProperty("action_items") public List<String> actionItems = new ArrayList<>();
    }

    /** Consecutive segments from one speaker, merged. */
    public static final class Turn {
        /** The display name, null when diarisation did not run. */
        public final String speaker;
        public final long startMs;
        public long endMs;
        public String text;

        Turn(String speaker, long startMs, long endMs, String text) {
            this.speaker = speaker;
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Transcript {
        /**
         * Full text, paragraphed. The cleanup output when that feature ran,
         * and the raw decode otherwise.
         */
        @JsonProperty("text") public String text;
        /**
         * The raw decode, kept even when cleanup ran so the verbatim wording
         * is never lost.
         */
        @JsonProperty("verbatim_text") public String verbatimText;
        @JsonProperty("segments") public List<Segment> segments = new ArrayList<>();
        @JsonProperty("speakers") public List<Speaker> speakers = new ArrayList<>();
        @JsonProperty("chapters") public List<Chapter> chapters = new ArrayList<>();
        @JsonProperty("summary") public Summary summary;
        @JsonProperty("language") public String language;
        @JsonProperty("translated_to") public String translatedTo;
        @JsonProperty("confidence") public Double confidence;
        @JsonProperty("word_count") public int wordCount;
        @JsonProperty("model") public Model model;
        @JsonProperty("model_version") public String modelVersion;

        /** A speaker's display name, or the id when it is unknown. */
        @JsonIgnore
        public String speakerLabel(String id) {
            if (id == null) {
                return null;
            }
            for (Speaker speaker : speakers) {
                if (id.equals(speaker.id)) {
                    return speaker.label;
                }
            }
            return id;
        }

        /**
         * Segments merged into speaker turns.
         *
         * <p>The decoder emits a segment every few seconds; read one per line
         * they are a list of fragments rather than speech.
         */
        @JsonIgnore
        public List<Turn> turns() {
            List<Turn> turns = new ArrayList<>();
            for (Segment segment : segments) {
                if (segment.text == null) {
                    continue;
                }
                String text = segment.text.strip();
                if (text.isEmpty()) {
                    continue;
                }
                String label = speakerLabel(segment.speaker);
                Turn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
                if (last != null && java.util.Objects.equals(last.speaker, label)) {
                    last.text = last.text + " " + text;
                    last.endMs = segment.endMs;
                } else {
                    turns.add(new Turn(label, segment.startMs, segment.endMs, text));
                }
            }
            return turns;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Failure {
        @JsonProperty("kind") public FailureKind kind;
        @JsonProperty("message") public String message;
        @JsonProperty("retryable") public boolean retryable;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class JobExport {
        @JsonProperty("format") public ExportFormat format;
        @JsonProperty("size_bytes") public long sizeBytes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Job {
        @JsonProperty("id") public String id;
        @JsonProperty("status") public JobStatus status;
        /** 0 to 1, measured where it can be and nominal otherwise. */
        @JsonProperty("progress") public double progress;
        @JsonProperty("source") public Source source;
        @JsonProperty("media") public MediaMetadata media;
        @JsonProperty("model") public Model model;
        /**
         * What actually ran. Compare against what was asked for: unentitled
         * features are dropped rather than failing the submission.
         */
        @JsonProperty("features") public List<Feature> features = new ArrayList<>();
        @JsonProperty("transcript") public Transcript transcript;
        @JsonProperty("exports") public List<JobExport> exports = new ArrayList<>();
        @JsonProperty("failure") public Failure failure;
        /** Zero until the media duration is known. */
        @JsonProperty("credits_charged") public long creditsCharged;
        @JsonProperty("share_slug") public String shareSlug;
        @JsonProperty("batch_id") public String batchId;
        @JsonProperty("duration_secs") public Double durationSecs;
        @JsonProperty("created_at") public String createdAt;
        @JsonProperty("completed_at") public String completedAt;

        /** Something worth showing in a list, falling back to the source. */
        @JsonIgnore
        public String title() {
            if (media != null && media.title != null && !media.title.isEmpty()) {
                return media.title;
            }
            return source == null ? id : source.display();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TranscribeResponse {
        @JsonProperty("job") public Job job;
        @JsonProperty("estimated_credits") public Long estimatedCredits;
        /** Accounts for current queue depth. */
        @JsonProperty("eta_secs") public Double etaSecs;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Estimate {
        @JsonProperty("media") public MediaMetadata media;
        @JsonProperty("duration_secs") public double durationSecs;
        @JsonProperty("duration_display") public String durationDisplay;
        @JsonProperty("model") public Model model;
        @JsonProperty("credits") public long credits;
        /**
         * Accounts for the monthly allowance, purchased minutes and any
         * overdraft. This is the field to branch on.
         */
        @JsonProperty("affordable") public boolean affordable;
        @JsonProperty("remaining_credits") public long remainingCredits;
        @JsonProperty("eta_secs") public Double etaSecs;

        /** The cost in standard minutes, which is how plans are quoted. */
        @JsonIgnore
        public double standardMinutes() {
            return creditsToStandardMinutes(credits);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Upload {
        @JsonProperty("upload_id") public String uploadId;
        /** The original name, reduced by the server to something safe. */
        @JsonProperty("filename") public String filename;
        @JsonProperty("duration_secs") public double durationSecs;
        @JsonProperty("size_bytes") public long sizeBytes;
        /** What the recording costs on Weave with no features. */
        @JsonProperty("credits") public long credits;
        /** When the staged file is swept if no job claims it. */
        @JsonProperty("expires_at") public String expiresAt;

        /** The source to submit for this upload. */
        @JsonIgnore
        public Source source() {
            return Source.upload(uploadId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Batch {
        @JsonProperty("id") public String id;
        @JsonProperty("source") public Source source;
        @JsonProperty("title") public String title;
        @JsonProperty("total") public int total;
        @JsonProperty("complete") public int complete;
        @JsonProperty("failed") public int failed;
        @JsonProperty("jobs") public List<Job> jobs = new ArrayList<>();
        @JsonProperty("created_at") public String createdAt;

        /** Whether every child job is terminal. */
        @JsonIgnore
        public boolean isFinished() {
            return complete + failed >= total;
        }

        /** The fraction of child jobs that are terminal. */
        @JsonIgnore
        public double progress() {
            return total == 0 ? 1.0 : (complete + failed) / (double) total;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class JobPage {
        @JsonProperty("jobs") public List<Job> jobs = new ArrayList<>();
        @JsonProperty("total") public long total;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Language {
        /** A BCP-47 tag, e.g. "en" or "pt-BR". */
        @JsonProperty("code") public String code;
        @JsonProperty("name") public String name;
        /** The name in the language itself. */
        @JsonProperty("native_name") public String nativeName;
        /**
         * Whether this language can be translated <em>into</em>. Every entry
         * can be transcribed; fewer can be targets.
         */
        @JsonProperty("translation_target") public boolean translationTarget;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class LanguageList {
        @JsonProperty("languages") List<Language> languages = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Share {
        @JsonProperty("slug") public String slug;
        @JsonProperty("job_id") public String jobId;
        @JsonProperty("indexable") public boolean indexable;
        @JsonProperty("hide_timestamps") public boolean hideTimestamps;
        @JsonProperty("show_summary") public boolean showSummary;
        @JsonProperty("expires_at") public String expiresAt;
        @JsonProperty("created_at") public String createdAt;
    }

    /**
     * What is POSTed to a job's webhook_url when it finishes.
     *
     * <p>Verify {@code X-Voxloom-Signature} on every delivery before trusting
     * this: the endpoint is a public URL.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class WebhookPayload {
        /** "job." plus the status. */
        @JsonProperty("event") public String event;
        @JsonProperty("job") public Job job;
        @JsonProperty("sent_at") public String sentAt;
    }
}
