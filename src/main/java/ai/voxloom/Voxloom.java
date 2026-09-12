package ai.voxloom;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Official Java client for the <a href="https://voxloom.ai">Voxloom</a>
 * transcription API.
 *
 * <p>Turns a video, podcast or recording into text with punctuation,
 * paragraphs, speaker labels, chapters and a summary, plus subtitle files.
 *
 * <pre>{@code
 * Voxloom client = Voxloom.create();   // reads VOXLOOM_API_KEY
 *
 * Models.Job job = client.transcribeAndWait(
 *         Source.youtube("https://youtu.be/VIDEO_ID"),
 *         new TranscribeOptions()
 *                 .feature(Feature.DIARIZATION)
 *                 .feature(Feature.SUMMARY)
 *                 .speakerCount(2),
 *         new WaitOptions());
 *
 * for (Models.Turn turn : job.transcript.turns()) {
 *     System.out.println(turn.speaker + ": " + turn.text);
 * }
 * }</pre>
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class Voxloom {

    /** The hosted API. */
    public static final String DEFAULT_BASE_URL = "https://voxloom.ai";

    /** The fastest the API asks to be polled. */
    public static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(2);

    public static final String VERSION = "1.0.0";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // An unknown field is not an error: a server that adds one must
            // not break an older client.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final String userAgent;

    private Voxloom(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.http = builder.http;
        this.requestTimeout = builder.requestTimeout;
        this.userAgent = builder.userAgent;
    }

    /** A client reading the key from {@code VOXLOOM_API_KEY}. */
    public static Voxloom create() {
        return builder().build();
    }

    /** A client with an explicit key. */
    public static Voxloom withKey(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link Voxloom}. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private HttpClient http;
        private Duration requestTimeout = Duration.ofSeconds(60);
        private String userAgent = "voxloom-java/" + VERSION;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Override the API root, for self-hosting or testing. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Supply the {@link HttpClient} to use, for a proxy or a shared pool. */
        public Builder httpClient(HttpClient http) {
            this.http = http;
            return this;
        }

        /**
         * Per-request timeout. Polling is driven separately, so this bounds
         * one HTTP call rather than a whole transcription.
         */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Append a product token, so an application identifies itself. */
        public Builder userAgent(String agent) {
            this.userAgent = "voxloom-java/" + VERSION + " " + agent;
            return this;
        }

        public Voxloom build() {
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = System.getenv("VOXLOOM_API_KEY");
            }
            if (apiKey == null || apiKey.isEmpty()) {
                throw new VoxloomException(
                        "voxloom: an API key is required. Pass it to Voxloom.withKey or "
                                + "set VOXLOOM_API_KEY. Create one under API keys at "
                                + "https://voxloom.ai/dashboard");
            }
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = System.getenv("VOXLOOM_BASE_URL");
            }
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = DEFAULT_BASE_URL;
            }
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            if (http == null) {
                http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return new Voxloom(this);
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private HttpRequest.Builder request(String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .timeout(timeout == null ? requestTimeout : timeout);
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new TransportException(
                    "voxloom: " + request.method() + " " + request.uri().getPath()
                            + ": " + e.getMessage(),
                    e);
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it: a caller waiting on
            // this thread needs to see the interruption.
            Thread.currentThread().interrupt();
            throw new TransportException("voxloom: interrupted", e);
        }
    }

    private byte[] body(HttpRequest request) {
        HttpResponse<byte[]> response = send(request);
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        if (response.statusCode() >= 400) {
            throw error(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
        }
        return bytes;
    }

    private static ApiException error(int status, String body) {
        String name = "HTTP " + status;
        String details = null;
        try {
            Map<String, Object> parsed =
                    MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object error = parsed.get("error");
            Object explanation = parsed.get("details");
            if (error != null) {
                name = String.valueOf(error);
            }
            if (explanation != null) {
                details = String.valueOf(explanation);
            }
        } catch (IOException ignored) {
            // Something in front of the API answered, e.g. an nginx error
            // page. Surface a prefix of it rather than losing the reason.
            String text = body.strip();
            if (!text.isEmpty()) {
                details = text.length() > 400 ? text.substring(0, 400) : text;
            }
        }
        return ApiException.of(status, name, details, body);
    }

    private <T> T parse(byte[] bytes, Class<T> type) {
        if (bytes.length == 0) {
            return null;
        }
        try {
            return MAPPER.readValue(bytes, type);
        } catch (IOException e) {
            throw new VoxloomException("voxloom: could not decode the response", e);
        }
    }

    private String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String encode(String segment) {
        // A job id containing a slash must not reach another route, and
        // URLEncoder is form encoding: it turns a space into "+", which is
        // wrong in a path. Percent-encode by hand instead.
        StringBuilder out = new StringBuilder(segment.length());
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int value = b & 0xff;
            boolean unreserved = (value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '-' || value == '_' || value == '.' || value == '~';
            if (unreserved) {
                out.append((char) value);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", value));
            }
        }
        return out.toString();
    }

    private static String query(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            out.append(out.length() == 0 ? '?' : '&')
                    .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    // ── transcription ──────────────────────────────────────────────────────

    /**
     * Submit one recording. <strong>This charges the account.</strong>
     *
     * <p>Credits are deducted at submission rather than on completion, so a
     * queued job has already been paid for.
     */
    public Models.Job transcribe(Source source, TranscribeOptions options) {
        Objects.requireNonNull(source, "source");
        if (source.isBatch()) {
            throw new VoxloomException(
                    "voxloom: that is a playlist or channel source; use transcribeBatch, "
                            + "which quotes the whole batch before starting any of it");
        }
        String payload = json(requestBody(source, options));
        byte[] bytes = body(request("/api/v1/transcribe", null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build());
        Models.TranscribeResponse response = parse(bytes, Models.TranscribeResponse.class);
        return response.job;
    }

    /**
     * Submit a playlist or channel, up to 25 videos.
     *
     * <p>The whole batch is quoted before any of it starts, so one the balance
     * cannot cover is refused rather than part-run.
     */
    public Models.Batch transcribeBatch(Source source, TranscribeOptions options) {
        String payload = json(requestBody(source, options));
        byte[] bytes = body(request("/api/v1/transcribe/batch", null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build());
        return parse(bytes, Models.Batch.class);
    }

    /**
     * What a job would cost. Charges nothing.
     *
     * <p>Branch on {@code affordable}, which accounts for the monthly
     * allowance, purchased minutes and any overdraft.
     */
    public Models.Estimate estimate(Source source, Model model, Collection<Feature> features) {
        ObjectNode node = MAPPER.createObjectNode();
        node.set("source", MAPPER.valueToTree(source));
        if (model != null) {
            node.put("model", model.wire());
        }
        if (features != null && !features.isEmpty()) {
            node.set("features", MAPPER.valueToTree(sorted(features)));
        }
        byte[] bytes = body(request("/api/v1/transcribe/estimate", null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(node.toString()))
                .build());
        return parse(bytes, Models.Estimate.class);
    }

    /**
     * Stage a file from disk. Charges nothing.
     *
     * <p>Submit the result with {@code transcribe(upload.source(), ..)}. The
     * staged file is swept after six hours if no job claims it.
     */
    public Models.Upload uploadFile(Path path) {
        try {
            return upload(path.getFileName().toString(), Files.readAllBytes(path));
        } catch (IOException e) {
            throw new VoxloomException("voxloom: reading " + path, e);
        }
    }

    /** Stage media from memory. Charges nothing. */
    public Models.Upload upload(String filename, byte[] content) {
        if (filename == null || filename.isEmpty()) {
            throw new VoxloomException("voxloom: a filename is required to upload");
        }

        String boundary = "----voxloom" + Long.toHexString(new Random().nextLong())
                + Long.toHexString(System.nanoTime());
        byte[] payload = multipart(boundary, sanitiseFilename(filename), content);

        byte[] bytes = body(request("/api/v1/transcribe/upload",
                        // A large upload over a domestic connection does not
                        // fit in the default request timeout.
                        Duration.ofHours(1))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build());
        return parse(bytes, Models.Upload.class);
    }

    static byte[] multipart(String boundary, String filename, byte[] content) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";

        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[headBytes.length + content.length + tailBytes.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(content, 0, out, headBytes.length, content.length);
        System.arraycopy(tailBytes, 0, out, headBytes.length + content.length, tailBytes.length);
        return out;
    }

    /**
     * Strip what cannot appear in a multipart part header.
     *
     * <p>A quote would close the quoted string early and a CR or LF would
     * start a new header line. The server derives its own display name from
     * this anyway.
     */
    static String sanitiseFilename(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String basename = slash >= 0 ? name.substring(slash + 1) : name;
        StringBuilder out = new StringBuilder(basename.length());
        basename.codePoints().forEach(codePoint -> {
            if (codePoint == '"' || codePoint == '\\' || Character.isISOControl(codePoint)) {
                return;
            }
            out.appendCodePoint(codePoint);
        });
        return out.toString();
    }

    private ObjectNode requestBody(Source source, TranscribeOptions options) {
        ObjectNode node = MAPPER.createObjectNode();
        node.set("source", MAPPER.valueToTree(source));
        if (options == null) {
            return node;
        }

        if (options.model != null) {
            node.put("model", options.model.wire());
        }

        // TreeSet deduplicates and orders, so the same options always produce
        // the same request.
        TreeSet<Feature> features = new TreeSet<>(options.features);
        if (options.translateTo != null) {
            // Asking for a target language without the feature is always a
            // mistake, and the server would silently not translate.
            features.add(Feature.TRANSLATION);
            node.put("translate_to", options.translateTo);
        }
        if (!features.isEmpty()) {
            node.set("features", MAPPER.valueToTree(new ArrayList<>(features)));
        }

        if (options.language != null) {
            node.put("language", options.language);
        }
        if (!options.vocabulary.isEmpty()) {
            node.set("vocabulary", MAPPER.valueToTree(options.vocabulary));
        }
        if (options.speakerCount != null) {
            node.put("speaker_count", options.speakerCount);
        }
        if (!options.formats.isEmpty()) {
            node.set("formats", MAPPER.valueToTree(options.formats));
        }
        if (options.share) {
            node.put("share", true);
        }
        if (options.webhookUrl != null) {
            if (!options.webhookUrl.startsWith("https://")) {
                throw new VoxloomException("voxloom: webhook URLs must use HTTPS");
            }
            node.put("webhook_url", options.webhookUrl);
        }
        return node;
    }

    private static List<Feature> sorted(Collection<Feature> features) {
        return new ArrayList<>(new TreeSet<>(features));
    }

    // ── jobs ───────────────────────────────────────────────────────────────

    /** A job, including its transcript once one exists. */
    public Models.Job job(String id) {
        byte[] bytes = body(request("/api/v1/jobs/" + encode(id), null).GET().build());
        return parse(bytes, Models.Job.class);
    }

    /** One page of jobs. */
    public Models.JobPage jobs(ListOptions options) {
        ListOptions effective = options == null ? new ListOptions() : options;
        byte[] bytes = body(
                request("/api/v1/jobs" + query(effective.toQuery()), null).GET().build());
        return parse(bytes, Models.JobPage.class);
    }

    /**
     * Every job, fetched a page at a time.
     *
     * <p>Stops when a page comes back short or empty, so a server whose
     * {@code total} changes mid-pagination cannot loop forever.
     */
    public List<Models.Job> allJobs(ListOptions options) {
        ListOptions effective = options == null ? new ListOptions() : options;
        int pageSize = effective.limit == null ? 50 : Math.max(1, effective.limit);

        List<Models.Job> collected = new ArrayList<>();
        int offset = effective.offset == null ? 0 : effective.offset;
        while (true) {
            ListOptions page = new ListOptions()
                    .limit(pageSize)
                    .offset(offset)
                    .status(effective.status)
                    .search(effective.search);
            Models.JobPage fetched = jobs(page);
            collected.addAll(fetched.jobs);
            if (fetched.jobs.isEmpty() || fetched.jobs.size() < pageSize) {
                return collected;
            }
            offset += fetched.jobs.size();
        }
    }

    /**
     * Stop a running job. The unprocessed remainder is refunded; what was
     * already transcribed stays charged.
     */
    public Models.Job cancelJob(String id) {
        return jobAction(id, "cancel");
    }

    /**
     * Re-run a failed job. Charged again, because the original was refunded.
     * Only failures the server marked retryable can succeed.
     */
    public Models.Job retryJob(String id) {
        return jobAction(id, "retry");
    }

    private Models.Job jobAction(String id, String action) {
        byte[] bytes = body(request("/api/v1/jobs/" + encode(id) + "/" + action, null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());
        return parse(bytes, Models.Job.class);
    }

    /** Delete a job and its transcript. Not reversible, and not refunded. */
    public void deleteJob(String id) {
        body(request("/api/v1/jobs/" + encode(id), null).DELETE().build());
    }

    /** Rename a speaker across the whole transcript, in one operation. Free. */
    public Models.Job renameSpeaker(String id, String speakerId, String label) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("speaker_id", speakerId);
        node.put("label", label);
        byte[] bytes = body(request("/api/v1/jobs/" + encode(id) + "/speakers", null)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(node.toString()))
                .build());
        return parse(bytes, Models.Job.class);
    }

    /** A batch and its child jobs. */
    public Models.Batch batch(String id) {
        byte[] bytes = body(request("/api/v1/batches/" + encode(id), null).GET().build());
        return parse(bytes, Models.Batch.class);
    }

    // ── exports ────────────────────────────────────────────────────────────

    /** Download a rendered export. Never charged. */
    public byte[] export(String id, ExportFormat format) {
        return body(request(
                "/api/v1/jobs/" + encode(id) + "/export/" + format.wire(),
                Duration.ofMinutes(5)).GET().build());
    }

    /** Download a text export as a string. */
    public String exportText(String id, ExportFormat format) {
        return new String(export(id, format), StandardCharsets.UTF_8);
    }

    /** Download an export straight to disk. */
    public Path saveExport(String id, ExportFormat format, Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, export(id, format));
            return path;
        } catch (IOException e) {
            throw new VoxloomException("voxloom: writing " + path, e);
        }
    }

    // ── shares ─────────────────────────────────────────────────────────────

    /** The account's share links. */
    public List<Models.Share> shares() {
        byte[] bytes = body(request("/api/v1/shares", null).GET().build());
        try {
            return MAPPER.readValue(bytes, new TypeReference<List<Models.Share>>() {});
        } catch (IOException e) {
            throw new VoxloomException("voxloom: could not decode the response", e);
        }
    }

    /**
     * Publish a transcript at a public URL.
     *
     * <p>The page carries {@code noindex} unless {@code indexable} is set.
     */
    public Models.Share createShare(String jobId, boolean indexable, Integer expiresInDays) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("job_id", jobId);
        node.put("indexable", indexable);
        node.put("show_summary", true);
        if (expiresInDays != null) {
            node.put("expires_in_days", expiresInDays);
        }
        byte[] bytes = body(request("/api/v1/shares", null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(node.toString()))
                .build());
        return parse(bytes, Models.Share.class);
    }

    /** Stop a share link working. */
    public void revokeShare(String slug) {
        body(request("/api/v1/shares/" + encode(slug), null).DELETE().build());
    }

    // ── public ─────────────────────────────────────────────────────────────

    /**
     * Every language that can be transcribed.
     *
     * <p>Only entries with {@code translationTarget} can be translated into:
     * more languages can be transcribed than translated.
     */
    public List<Models.Language> languages() {
        byte[] bytes = body(request("/api/languages", null).GET().build());
        Models.LanguageList list = parse(bytes, Models.LanguageList.class);
        return list == null ? List.of() : list.languages;
    }

    // ── waiting ────────────────────────────────────────────────────────────

    /**
     * Poll until the job is terminal.
     *
     * @throws JobFailedException when the job failed, unless
     *     {@code ignoreFailure} is set
     * @throws JobTimeoutException when the budget runs out, carrying the job:
     *     it is still running and nothing extra has been charged
     */
    public Models.Job waitForJob(String id, WaitOptions options) {
        WaitOptions effective = options == null ? new WaitOptions() : options;
        Duration interval = effective.pollInterval.compareTo(MIN_POLL_INTERVAL) < 0
                ? MIN_POLL_INTERVAL
                : effective.pollInterval;
        long deadline = System.nanoTime() + effective.timeout.toNanos();

        while (true) {
            Models.Job job = job(id);
            if (effective.onProgress != null) {
                effective.onProgress.accept(job);
            }

            if (job.status.isTerminal()) {
                if (job.status == JobStatus.FAILED && !effective.ignoreFailure) {
                    throw new JobFailedException(job);
                }
                return job;
            }

            // Stop rather than poll early when less than a full interval is
            // left: sleeping a fraction and polling again defeats the floor.
            if (deadline - System.nanoTime() < interval.toNanos()) {
                throw new JobTimeoutException(job, effective.timeout);
            }
            sleep(interval);
        }
    }

    /**
     * Submit a recording and wait for the transcript.
     *
     * <p>Convenient, and the wrong shape for production: a long recording
     * means a thread held for minutes. Set a webhook URL instead.
     */
    public Models.Job transcribeAndWait(
            Source source, TranscribeOptions options, WaitOptions wait) {
        Models.Job job = transcribe(source, options);
        return waitForJob(job.id, wait);
    }

    /**
     * Poll until every job in a batch is terminal.
     *
     * <p>Returns the batch rather than throwing when children failed: one
     * video failing does not stop the rest, so inspect {@code batch.jobs}.
     */
    public Models.Batch waitForBatch(String id, Duration timeout, Duration pollInterval) {
        Duration interval = pollInterval == null || pollInterval.compareTo(MIN_POLL_INTERVAL) < 0
                ? MIN_POLL_INTERVAL
                : pollInterval;
        long deadline = System.nanoTime() + timeout.toNanos();

        while (true) {
            Models.Batch batch = batch(id);
            if (batch.isFinished()) {
                return batch;
            }
            if (deadline - System.nanoTime() < interval.toNanos()) {
                throw new VoxloomException(
                        "voxloom: batch " + id + " was unfinished after the timeout");
            }
            sleep(interval);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoxloomException("voxloom: interrupted while waiting", e);
        }
    }

    /** The choices for one submission. */
    public static final class TranscribeOptions {
        Model model;
        final List<Feature> features = new ArrayList<>();
        String language;
        String translateTo;
        final List<String> vocabulary = new ArrayList<>();
        Integer speakerCount;
        final List<ExportFormat> formats = new ArrayList<>();
        boolean share;
        String webhookUrl;

        public TranscribeOptions model(Model model) {
            this.model = model;
            return this;
        }

        public TranscribeOptions feature(Feature feature) {
            this.features.add(feature);
            return this;
        }

        public TranscribeOptions features(Collection<Feature> features) {
            this.features.addAll(features);
            return this;
        }

        /**
         * BCP-47 code of the spoken language, detected when unset.
         *
         * <p>Worth setting when known: detection reads the opening of the
         * recording, so a video that starts with music can be misdetected,
         * and that produces a confidently wrong transcript rather than an
         * error.
         */
        public TranscribeOptions language(String language) {
            this.language = language;
            return this;
        }

        /** BCP-47 target. Setting it adds {@link Feature#TRANSLATION}. */
        public TranscribeOptions translateTo(String language) {
            this.translateTo = language;
            return this;
        }

        /**
         * Names, products and jargon the model would otherwise mishear. Free,
         * and the largest accuracy improvement available on specialised audio.
         */
        public TranscribeOptions vocabulary(Collection<String> terms) {
            this.vocabulary.addAll(terms);
            return this;
        }

        /** More reliable than letting the clusterer guess. An interview is 2. */
        public TranscribeOptions speakerCount(int count) {
            this.speakerCount = count;
            return this;
        }

        public TranscribeOptions format(ExportFormat format) {
            this.formats.add(format);
            return this;
        }

        public TranscribeOptions share(boolean share) {
            this.share = share;
            return this;
        }

        /** HTTPS only. POSTed once when the job becomes terminal. */
        public TranscribeOptions webhookUrl(String url) {
            this.webhookUrl = url;
            return this;
        }
    }

    /** Filters for a job listing. */
    public static final class ListOptions {
        Integer limit;
        Integer offset;
        JobStatus status;
        String search;

        public ListOptions limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public ListOptions offset(Integer offset) {
            this.offset = offset;
            return this;
        }

        public ListOptions status(JobStatus status) {
            this.status = status;
            return this;
        }

        /** Matches against the media title. */
        public ListOptions search(String search) {
            this.search = search;
            return this;
        }

        Map<String, String> toQuery() {
            Map<String, String> params = new java.util.LinkedHashMap<>();
            if (limit != null) {
                params.put("limit", String.valueOf(limit));
            }
            if (offset != null && offset > 0) {
                params.put("offset", String.valueOf(offset));
            }
            if (status != null) {
                params.put("status", status.wire());
            }
            if (search != null && !search.isEmpty()) {
                params.put("search", search);
            }
            return params;
        }
    }

    /** Controls polling. */
    public static final class WaitOptions {
        Duration timeout = Duration.ofHours(1);
        Duration pollInterval = Duration.ofSeconds(3);
        Consumer<Models.Job> onProgress;
        boolean ignoreFailure;

        public WaitOptions timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Clamped up to {@link Voxloom#MIN_POLL_INTERVAL}. */
        public WaitOptions pollInterval(Duration interval) {
            this.pollInterval = interval;
            return this;
        }

        /** Called with each poll's job, for a progress bar. */
        public WaitOptions onProgress(Consumer<Models.Job> callback) {
            this.onProgress = callback;
            return this;
        }

        /** Return a failed job instead of throwing {@link JobFailedException}. */
        public WaitOptions ignoreFailure(boolean ignore) {
            this.ignoreFailure = ignore;
            return this;
        }
    }
}
