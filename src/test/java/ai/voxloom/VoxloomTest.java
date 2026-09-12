package ai.voxloom;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests against a real HTTP server from the JDK, so the request that goes on
 * the wire is the thing being asserted. The bugs in a client library are
 * almost always in what it sent.
 */
class VoxloomTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final Deque<int[]> statuses = new ArrayDeque<>();
    private final Deque<String> bodies = new ArrayDeque<>();
    private final List<Recorded> calls = new ArrayList<>();
    private String fallbackBody;
    private int fallbackStatus = 200;

    record Recorded(String method, String path, String query, String contentType,
                    String authorization, String userAgent, byte[] body) {
        JsonNode json() throws IOException {
            return MAPPER.readTree(body);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody;
        try (InputStream in = exchange.getRequestBody()) {
            requestBody = in.readAllBytes();
        }
        calls.add(new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("User-Agent"),
                requestBody));

        int status;
        String body;
        if (!statuses.isEmpty()) {
            status = statuses.poll()[0];
            body = bodies.poll();
        } else {
            status = fallbackStatus;
            body = fallbackBody == null ? "{}" : fallbackBody;
        }

        byte[] out = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
        }
        exchange.close();
    }

    /** A Deque cannot hold null, so an empty body is the empty string. */
    private void respond(int status, String body) {
        statuses.add(new int[] {status});
        bodies.add(body == null ? "" : body);
    }

    private void always(int status, String body) {
        fallbackStatus = status;
        fallbackBody = body;
    }

    private Voxloom client() {
        return Voxloom.builder().apiKey("sk_prod_test").baseUrl(baseUrl).build();
    }

    private static String completeJob() {
        return """
            {
              "id": "job-1",
              "status": "complete",
              "progress": 1.0,
              "source": { "kind": "youtube", "url": "https://youtu.be/x" },
              "model": "weave",
              "created_at": "2026-09-12T10:00:00Z",
              "media": { "title": "A video", "duration_secs": 120.0 },
              "credits_charged": 240,
              "transcript": {
                "text": "Hello there. This is a test. Good, thanks.",
                "language": "en",
                "word_count": 9,
                "model": "weave",
                "model_version": "large-v3-turbo",
                "segments": [
                  { "start_ms": 0, "end_ms": 2000, "text": "Hello there.", "speaker": "speaker_0" },
                  { "start_ms": 2000, "end_ms": 4000, "text": "This is a test.", "speaker": "speaker_0" },
                  { "start_ms": 65000, "end_ms": 67000, "text": "Good, thanks.", "speaker": "speaker_1" }
                ],
                "speakers": [
                  { "id": "speaker_0", "label": "Ada", "speaking_secs": 4.0, "segment_count": 2 },
                  { "id": "speaker_1", "label": "Grace", "speaking_secs": 2.0, "segment_count": 1 }
                ]
              }
            }
            """;
    }

    // ── construction ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a missing key fails at construction, not on the first call")
    void missingKeyFailsEarly() {
        VoxloomException error =
                assertThrows(VoxloomException.class, () -> Voxloom.withKey(""));
        assertTrue(error.getMessage().contains("VOXLOOM_API_KEY"),
                "the error should name the variable: " + error.getMessage());
    }

    @Test
    @DisplayName("a trailing slash on the base URL does not double up")
    void trailingSlash() {
        always(200, completeJob());
        Voxloom client = Voxloom.builder().apiKey("k").baseUrl(baseUrl + "///").build();
        client.job("job-1");
        assertEquals("/api/v1/jobs/job-1", calls.get(0).path());
    }

    @Test
    @DisplayName("every request is authenticated and identifies the SDK")
    void authenticated() {
        always(200, completeJob());
        client().job("job-1");
        assertEquals("Bearer sk_prod_test", calls.get(0).authorization());
        assertTrue(calls.get(0).userAgent().startsWith("voxloom-java/"),
                calls.get(0).userAgent());
    }

    // ── transcribe ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the source tag is kind, never type")
    void sourceTagIsKind() throws IOException {
        respond(200, "{\"job\":" + completeJob() + "}");
        client().transcribe(Source.youtube("https://youtu.be/x"), null);

        JsonNode source = calls.get(0).json().get("source");
        assertEquals("youtube", source.get("kind").asText());
        assertTrue(source.get("type") == null, "the request must not contain \"type\"");
    }

    @Test
    @DisplayName("asking for a target language requests translation")
    void translateToAddsTheFeature() throws IOException {
        respond(200, "{\"job\":" + completeJob() + "}");
        client().transcribe(
                Source.youtube("x"),
                new Voxloom.TranscribeOptions().translateTo("es"));

        JsonNode body = calls.get(0).json();
        assertEquals("es", body.get("translate_to").asText());
        List<String> features = new ArrayList<>();
        body.get("features").forEach(node -> features.add(node.asText()));
        assertTrue(features.contains("translation"), features.toString());
    }

    @Test
    @DisplayName("features are deduplicated and ordered")
    void featuresAreDeduplicated() throws IOException {
        respond(200, "{\"job\":" + completeJob() + "}");
        client().transcribe(
                Source.youtube("x"),
                new Voxloom.TranscribeOptions()
                        .feature(Feature.TRANSLATION)
                        .feature(Feature.SUMMARY)
                        .feature(Feature.TRANSLATION)
                        .translateTo("fr"));

        JsonNode features = calls.get(0).json().get("features");
        assertEquals(2, features.size(), features.toString());
        // Enum declaration order: SUMMARY comes before TRANSLATION.
        assertEquals("summary", features.get(0).asText());
        assertEquals("translation", features.get(1).asText());
    }

    @Test
    @DisplayName("omitted options are absent rather than null")
    void omittedOptionsAreAbsent() throws IOException {
        respond(200, "{\"job\":" + completeJob() + "}");
        client().transcribe(Source.youtube("x"), new Voxloom.TranscribeOptions());

        JsonNode body = calls.get(0).json();
        assertEquals(1, body.size(), "only the source should be sent: " + body);
        assertNotNull(body.get("source"));
    }

    @Test
    @DisplayName("a playlist is refused before any request")
    void playlistIsRefusedLocally() {
        VoxloomException error = assertThrows(VoxloomException.class,
                () -> client().transcribe(Source.playlist("https://youtube.com/playlist?list=x"), null));
        assertTrue(error.getMessage().contains("transcribeBatch"), error.getMessage());
        assertTrue(calls.isEmpty(), "no request should have been made");
    }

    @Test
    @DisplayName("a non-HTTPS webhook is refused before any request")
    void plainHttpWebhookRefused() {
        assertThrows(VoxloomException.class, () -> client().transcribe(
                Source.youtube("x"),
                new Voxloom.TranscribeOptions().webhookUrl("http://example.com/hook")));
        assertTrue(calls.isEmpty());
    }

    // ── errors ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("each status maps to its own exception")
    void statusesMapToExceptions() {
        record Case(int status, Class<? extends ApiException> type) {}
        List<Case> cases = List.of(
                new Case(400, ValidationException.class),
                new Case(401, AuthenticationException.class),
                new Case(402, OutOfCreditsException.class),
                new Case(403, PlanTooLowException.class),
                new Case(404, NotFoundException.class),
                new Case(429, RateLimitedException.class),
                new Case(503, ServerException.class));

        for (Case testCase : cases) {
            always(testCase.status(), "{\"error\":\"Nope\",\"details\":\"Because.\"}");
            ApiException error =
                    assertThrows(testCase.type(), () -> client().job("job-1"),
                            "status " + testCase.status());
            assertEquals(testCase.status(), error.statusCode());
            assertEquals("Because.", error.details());
        }
    }

    @Test
    @DisplayName("out of credits and plan too low are different exceptions")
    void billingErrorsAreDistinct() {
        // One means "buy more minutes and retry", the other means "this will
        // never work". A caller must not have to read a message.
        always(402, "{\"error\":\"Payment Required\",\"details\":\"No minutes.\"}");
        assertThrows(OutOfCreditsException.class, () -> client().job("job-1"));

        always(403, "{\"error\":\"Forbidden\",\"details\":\"Needs Studio.\"}");
        assertThrows(PlanTooLowException.class, () -> client().job("job-1"));
    }

    @Test
    @DisplayName("a non-JSON error body does not crash")
    void htmlErrorBody() {
        always(502, "<html>502 Bad Gateway</html>");
        ApiException error = assertThrows(ServerException.class, () -> client().job("job-1"));
        assertTrue(error.getMessage().contains("Bad Gateway"), error.getMessage());
        assertTrue(error.isRetryable());
    }

    @Test
    @DisplayName("retryability matches what a caller should do")
    void retryability() {
        assertTrue(ApiException.of(429, "x", null, "").isRetryable());
        assertTrue(ApiException.of(503, "x", null, "").isRetryable());
        assertFalse(ApiException.of(402, "x", null, "").isRetryable());
        assertFalse(ApiException.of(403, "x", null, "").isRetryable());
        assertFalse(ApiException.of(400, "x", null, "").isRetryable());
    }

    // ── waiting ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a finished job is not polled twice")
    void finishedJobPolledOnce() {
        respond(200, completeJob());
        Models.Job job = client().waitForJob("job-1",
                new Voxloom.WaitOptions().pollInterval(Duration.ofMillis(1)));
        assertEquals(JobStatus.COMPLETE, job.status);
        assertEquals(1, calls.size());
    }

    @Test
    @DisplayName("a timeout throws but keeps the job")
    void timeoutKeepsTheJob() {
        // The job is still running and nothing extra has been charged, so the
        // caller needs its id.
        always(200, completeJob().replace("\"complete\"", "\"transcribing\""));
        JobTimeoutException error = assertThrows(JobTimeoutException.class,
                () -> client().waitForJob("job-1",
                        new Voxloom.WaitOptions().timeout(Duration.ZERO)));
        assertEquals("job-1", error.job().id);
        assertTrue(error.getMessage().contains("still running"), error.getMessage());
    }

    @Test
    @DisplayName("a failed job throws with the reason and retryability")
    void failedJobThrows() {
        always(200, """
            {
              "id": "job-1", "status": "failed", "progress": 1.0,
              "source": { "kind": "youtube", "url": "x" },
              "model": "weave", "created_at": "t",
              "failure": {
                "kind": "media_unavailable",
                "message": "This video is private.",
                "retryable": false
              }
            }
            """);

        JobFailedException error =
                assertThrows(JobFailedException.class, () -> client().waitForJob("job-1", null));
        assertFalse(error.isRetryable());
        assertEquals(FailureKind.MEDIA_UNAVAILABLE, error.kind());
        assertTrue(error.refundsCredits(), "a private video is refunded");
        assertTrue(error.getMessage().contains("private"), error.getMessage());
    }

    @Test
    @DisplayName("a failure can be returned instead of thrown")
    void failureCanBeIgnored() {
        always(200, """
            {
              "id": "job-1", "status": "failed", "progress": 1.0,
              "source": { "kind": "youtube", "url": "x" },
              "model": "weave", "created_at": "t",
              "failure": { "kind": "timeout", "message": "Timed out.", "retryable": true }
            }
            """);

        Models.Job job = client().waitForJob("job-1",
                new Voxloom.WaitOptions().ignoreFailure(true));
        assertEquals(JobStatus.FAILED, job.status);
        assertTrue(job.failure.retryable);
    }

    @Test
    @DisplayName("progress is reported on every poll")
    void progressReported() {
        respond(200, completeJob()
                .replace("\"complete\"", "\"transcribing\"")
                .replace("\"progress\": 1.0", "\"progress\": 0.3"));
        respond(200, completeJob());

        List<Double> seen = new ArrayList<>();
        client().waitForJob("job-1", new Voxloom.WaitOptions()
                .pollInterval(Duration.ofMillis(1))
                .onProgress(job -> seen.add(job.progress)));

        assertEquals(List.of(0.3, 1.0), seen);
    }

    @Test
    @DisplayName("the poll interval is never faster than the API asks for")
    void pollFloorIsEnforced() {
        always(200, completeJob().replace("\"complete\"", "\"queued\""));
        AtomicInteger before = new AtomicInteger(calls.size());

        long started = System.nanoTime();
        assertThrows(JobTimeoutException.class, () -> client().waitForJob("job-1",
                new Voxloom.WaitOptions()
                        .timeout(Duration.ofMillis(2_500))
                        .pollInterval(Duration.ofMillis(1))));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs >= 2_000, "it polled faster than the floor: " + elapsedMs + "ms");
        assertTrue(calls.size() - before.get() <= 2,
                "made " + (calls.size() - before.get()) + " requests in 2.5s");
    }

    // ── listing ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("list filters become query parameters")
    void listFilters() {
        respond(200, "{\"jobs\":[],\"total\":0}");
        client().jobs(new Voxloom.ListOptions()
                .limit(10)
                .status(JobStatus.COMPLETE)
                .search("podcast"));

        String query = calls.get(0).query();
        assertTrue(query.contains("limit=10"), query);
        assertTrue(query.contains("status=complete"), query);
        assertTrue(query.contains("search=podcast"), query);
    }

    @Test
    @DisplayName("paging stops on a short page")
    void pagingStopsOnShortPage() {
        // A server whose total changes mid-pagination must not loop forever.
        respond(200, "{\"jobs\":[" + completeJob() + "," + completeJob() + "],\"total\":99}");
        respond(200, "{\"jobs\":[" + completeJob() + "],\"total\":99}");

        List<Models.Job> jobs = client().allJobs(new Voxloom.ListOptions().limit(2));
        assertEquals(3, jobs.size());
    }

    @Test
    @DisplayName("paging stops on an empty page")
    void pagingStopsOnEmptyPage() {
        respond(200, "{\"jobs\":[" + completeJob() + "," + completeJob() + "],\"total\":2}");
        respond(200, "{\"jobs\":[],\"total\":2}");
        assertEquals(2, client().allJobs(new Voxloom.ListOptions().limit(2)).size());
    }

    @Test
    @DisplayName("an id with a slash cannot escape its path segment")
    void idCannotEscapeSegment() {
        always(200, completeJob());
        client().job("../../admin");

        String path = calls.get(0).path();
        assertTrue(path.contains("%2F"), "the slashes were not escaped: " + path);
        assertFalse(path.endsWith("/admin"), "the id escaped: " + path);
    }

    @Test
    @DisplayName("a space in an id is percent-encoded, not turned into a plus")
    void spacesArePercentEncoded() {
        // URLEncoder is form encoding and produces "+", which is a literal
        // plus in a path rather than a space.
        always(200, completeJob());
        client().job("a b");
        assertTrue(calls.get(0).path().contains("%20"), calls.get(0).path());
        assertFalse(calls.get(0).path().contains("+"), calls.get(0).path());
    }

    @Test
    @DisplayName("a 204 delete succeeds")
    void deleteSucceeds() {
        respond(204, "");
        assertDoesNotThrow(() -> client().deleteJob("job-1"));
        assertEquals("DELETE", calls.get(0).method());
    }

    // ── uploads ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("an upload is sent as multipart")
    void uploadIsMultipart() {
        respond(200, """
            {
              "upload_id": "up-1", "filename": "clip", "duration_secs": 12.0,
              "size_bytes": 10, "credits": 24, "expires_at": "t"
            }
            """);

        Models.Upload upload = client().upload("clip.wav", "fake audio".getBytes(StandardCharsets.UTF_8));
        assertEquals("up-1", upload.uploadId);
        assertEquals(Source.upload("up-1"), upload.source());

        Recorded sent = calls.get(0);
        assertTrue(sent.contentType().startsWith("multipart/form-data; boundary="),
                sent.contentType());
        String body = new String(sent.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("name=\"file\""), body);
        assertTrue(body.contains("fake audio"), body);
    }

    @Test
    @DisplayName("an upload without a filename is refused")
    void uploadNeedsAFilename() {
        assertThrows(VoxloomException.class, () -> client().upload("", new byte[] {1}));
        assertTrue(calls.isEmpty());
    }

    @Test
    @DisplayName("a hostile filename cannot break the part header")
    void hostileFilename() {
        String cleaned = Voxloom.sanitiseFilename("evil\"; name=\"x\r\ninjected: 1\u0000");
        for (String forbidden : List.of("\"", "\\", "\r", "\n", "\u0000")) {
            assertFalse(cleaned.contains(forbidden),
                    forbidden + " survived: " + cleaned);
        }
    }

    @Test
    @DisplayName("a sanitised filename loses its directory")
    void filenameLosesDirectory() {
        assertEquals("passwd", Voxloom.sanitiseFilename("/tmp/../../etc/passwd"));
        assertEquals("clip.wav", Voxloom.sanitiseFilename("C:\\Users\\me\\clip.wav"));
    }

    @Test
    @DisplayName("the multipart body is well formed around the content")
    void multipartStructure() {
        byte[] body = Voxloom.multipart("BOUNDARY", "clip.wav", new byte[] {1, 2, 3});
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--BOUNDARY\r\n"), text);
        assertTrue(text.endsWith("\r\n--BOUNDARY--\r\n"), text);
        assertTrue(text.contains("filename=\"clip.wav\""), text);
    }

    // ── exports ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("an export comes back as bytes and as text")
    void exportComesBack() {
        String srt = "1\n00:00:00,000 --> 00:00:02,000\nHello.\n\n";
        respond(200, srt);
        assertEquals(srt, client().exportText("job-1", ExportFormat.SRT));
        assertTrue(calls.get(0).path().endsWith("/export/srt"), calls.get(0).path());
    }

    // ── languages ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("translation targets are distinguished")
    void translationTargets() {
        respond(200, """
            { "languages": [
                { "code": "en", "name": "English", "native_name": "English", "translation_target": true },
                { "code": "yo", "name": "Yoruba", "native_name": "Yoruba" }
            ] }
            """);
        List<Models.Language> languages = client().languages();
        assertTrue(languages.get(0).translationTarget);
        assertFalse(languages.get(1).translationTarget);
    }

    // ── model helpers ─────────────────────────────────────────────────────

    @Test
    @DisplayName("terminal and running partition every status")
    void statusPartition() {
        for (JobStatus status : JobStatus.values()) {
            assertTrue(status.isTerminal() != status.isRunning(), status.name());
        }
    }

    @Test
    @DisplayName("only running out of credits is unrefunded")
    void refundRules() {
        for (FailureKind kind : FailureKind.values()) {
            boolean expected = kind != FailureKind.INSUFFICIENT_CREDITS;
            assertEquals(expected, kind.refundsCredits(), kind.name());
        }
    }

    @Test
    @DisplayName("an unknown status or failure kind does not break parsing")
    void unknownEnumValues() {
        // A newer server must not break an older client mid-pipeline.
        assertEquals(JobStatus.QUEUED, JobStatus.fromWire("transmogrifying"));
        assertEquals(FailureKind.UNKNOWN, FailureKind.fromWire("something_new"));
        assertEquals(Model.WEAVE, Model.fromWire("brand_new_model"));
    }

    @Test
    @DisplayName("batch sources are identified")
    void batchSources() {
        assertTrue(Source.playlist("x").isBatch());
        assertTrue(Source.channel("x").isBatch());
        assertFalse(Source.youtube("x").isBatch());
        assertFalse(Source.url("x").isBatch());
        assertFalse(Source.upload("x").isBatch());
    }

    @Test
    @DisplayName("a blank URL is refused at construction")
    void blankUrlRefused() {
        assertThrows(IllegalArgumentException.class, () -> Source.youtube("  "));
        assertThrows(NullPointerException.class, () -> Source.url(null));
    }

    @Test
    @DisplayName("a second of audio costs one credit, whichever name is used")
    void multipliers() {
        // This asserted 1, 2 and 4 for the three tiers. The retired names are
        // deprecated aliases for the one model, so they cost the same.
        assertEquals(1, Model.WEAVE.multiplier());
        assertEquals(1, Model.THREAD.multiplier());
        assertEquals(1, Model.TAPESTRY.multiplier());
        assertEquals("weave", Model.TAPESTRY.wire());
    }

    @Test
    @DisplayName("credits convert at one second each")
    void creditConversion() {
        // 60 credits is a minute. It was 120 while three model tiers existed
        // and figures were quoted at the middle tier's 2x rate, so dividing
        // by 120 now reports half the minutes a balance is worth.
        assertEquals(1.0, Models.creditsToStandardMinutes(60));
        assertEquals(60.0, Models.creditsToStandardMinutes(3_600));
        assertEquals(0.0, Models.creditsToStandardMinutes(-100));
        assertEquals(60, Models.standardMinutesToCredits(1.0));
        assertEquals("1m", Models.formatCredits(60));
        assertEquals("1h 0m", Models.formatCredits(3_600));
    }

    @Test
    @DisplayName("durations and timestamps match the product")
    void formatting() {
        assertEquals("42s", Models.formatDuration(42));
        assertEquals("1m", Models.formatDuration(90));
        assertEquals("1h 1m", Models.formatDuration(3_661));
        assertEquals("0s", Models.formatDuration(-1));
        assertEquals("0:00", Models.formatTimestamp(0));
        assertEquals("1:05", Models.formatTimestamp(65_000));
        assertEquals("1:02:05", Models.formatTimestamp(3_725_000));
    }

    @Test
    @DisplayName("turns merge consecutive segments from one speaker")
    void turnsMerge() {
        always(200, completeJob());
        Models.Job job = client().job("job-1");

        List<Models.Turn> turns = job.transcript.turns();
        assertEquals(2, turns.size());
        assertEquals("Ada", turns.get(0).speaker);
        assertEquals("Hello there. This is a test.", turns.get(0).text);
        assertEquals(4_000, turns.get(0).endMs, "a turn ends at its last segment");
        assertEquals("Grace", turns.get(1).speaker);
    }

    @Test
    @DisplayName("an unknown speaker id falls back to the id")
    void unknownSpeakerId() {
        Models.Transcript transcript = new Models.Transcript();
        Models.Segment segment = new Models.Segment();
        segment.text = "x";
        segment.speaker = "speaker_9";
        transcript.segments = List.of(segment);

        assertEquals("speaker_9", transcript.speakerLabel("speaker_9"));
        assertEquals("speaker_9", transcript.turns().get(0).speaker);
    }

    @Test
    @DisplayName("blank segments do not become blank turns")
    void blankSegments() {
        Models.Transcript transcript = new Models.Transcript();
        Models.Segment blank = new Models.Segment();
        blank.text = "   ";
        Models.Segment real = new Models.Segment();
        real.text = "Real.";
        transcript.segments = List.of(blank, real);

        assertEquals(1, transcript.turns().size());
        assertEquals("Real.", transcript.turns().get(0).text);
    }

    @Test
    @DisplayName("a batch reports progress and completion")
    void batchProgress() {
        Models.Batch batch = new Models.Batch();
        batch.total = 4;
        batch.complete = 2;
        batch.failed = 1;
        assertEquals(0.75, batch.progress());
        assertFalse(batch.isFinished());

        Models.Batch empty = new Models.Batch();
        assertEquals(1.0, empty.progress(), "an empty batch is finished, not a division by zero");
        assertTrue(empty.isFinished());
    }

    @Test
    @DisplayName("a job title falls back to the source")
    void jobTitleFallback() {
        Models.Job job = new Models.Job();
        job.id = "job-1";
        job.source = Source.youtube("https://youtu.be/x");
        assertEquals("https://youtu.be/x", job.title());

        job.media = new Models.MediaMetadata();
        job.media.title = "A video";
        assertEquals("A video", job.title());
    }

    @Test
    @DisplayName("an estimate quotes standard minutes")
    void estimateStandardMinutes() {
        Models.Estimate estimate = new Models.Estimate();
        // 20 minutes of audio at one credit a second.
        estimate.credits = 1_200;
        assertEquals(20.0, estimate.standardMinutes());
    }

    @Test
    @DisplayName("unknown response fields are ignored rather than fatal")
    void unknownFieldsIgnored() {
        // An SDK you have to upgrade before you can ignore a new field is an
        // SDK that gets in the way.
        always(200, completeJob().replace("\"id\": \"job-1\"",
                "\"id\": \"job-1\", \"brand_new_field\": { \"nested\": 1 }"));
        assertDoesNotThrow(() -> client().job("job-1"));
    }
}
