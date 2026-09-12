# voxloom-java

Official Java client for the [Voxloom](https://voxloom.ai) transcription API.

```xml
<dependency>
  <groupId>ai.voxloom</groupId>
  <artifactId>voxloom</artifactId>
  <version>1.0.0</version>
</dependency>
```

Java 17 or newer. HTTP is `java.net.http`, built in; Jackson is the only
runtime dependency.

Get an API key from **API keys** in your
[dashboard](https://voxloom.ai/dashboard); API access is included from the
Studio plan upwards.

## Transcribe something

```java
import ai.voxloom.*;

Voxloom client = Voxloom.create(); // reads VOXLOOM_API_KEY

Models.Job job = client.transcribeAndWait(
        Source.youtube("https://youtu.be/VIDEO_ID"),
        new Voxloom.TranscribeOptions()
                .feature(Feature.DIARIZATION)
                .feature(Feature.SUMMARY)
                .speakerCount(2),
        new Voxloom.WaitOptions());

for (Models.Turn turn : job.transcript.turns()) {
    System.out.println(turn.speaker + ": " + turn.text);
}
```

Instances are immutable and safe to share between threads.

## Check the cost first

Transcription is charged by the length of the recording, so quote it before
committing:

```java
Models.Estimate estimate = client.estimate(
        Source.youtube("https://youtu.be/VIDEO_ID"), Model.TAPESTRY, null);

System.out.printf("%s costs %.1f minutes%n",
        estimate.durationDisplay, estimate.standardMinutes());

if (!estimate.affordable) {
    throw new IllegalStateException("Not enough minutes left.");
}
```

`estimate` charges nothing, and `affordable` already accounts for the monthly
allowance, purchased minutes and any overdraft.

## Do not hold a thread open

`transcribeAndWait` is convenient and the wrong shape for production: a long
recording means a thread parked for minutes. Submit and take a webhook:

```java
Models.Job job = client.transcribe(
        Source.youtube("https://youtu.be/VIDEO_ID"),
        new Voxloom.TranscribeOptions().webhookUrl("https://example.com/voxloom-hook"));
```

Verify `X-Voxloom-Signature` on every delivery. See
[the webhook docs](https://voxloom.ai/docs/reference/webhooks).

## Upload a file

Two steps, so you see the real length and price before anything is charged:

```java
Models.Upload upload = client.uploadFile(Path.of("board-meeting.mov"));
System.out.printf("%.0f minutes%n", upload.durationSecs / 60);

Models.Job job = client.transcribeAndWait(
        upload.source(), new Voxloom.TranscribeOptions(), new Voxloom.WaitOptions());
```

Up to 5 GB and 12 hours. The staged file is swept after six hours if no job
claims it.

## Subtitles

```java
client.saveExport(job.id, ExportFormat.SRT, Path.of("subtitles.srt"));
String vtt = client.exportText(job.id, ExportFormat.VTT);
```

Exports are free, and re-exporting an old transcript in another format costs
nothing.

## Playlists

```java
Models.Batch batch = client.transcribeBatch(
        Source.playlist("https://www.youtube.com/playlist?list=PLAYLIST_ID"), null);

batch = client.waitForBatch(batch.id, Duration.ofHours(2), Duration.ofSeconds(10));

for (Models.Job child : batch.jobs) {
    if (child.failure != null) {
        System.out.println(child.title() + ": " + child.failure.message);
    }
}
```

Up to 25 videos, quoted in full before any of them start. One failing does not
stop the rest, and each failure is refunded individually.

## Errors

Catch what to do about it, not a status code:

```java
try {
    Models.Job job = client.transcribeAndWait(source, options, wait);
} catch (OutOfCreditsException e) {
    // Buying more minutes makes this exact request work.
} catch (PlanTooLowException e) {
    // It will not, however many minutes you buy.
} catch (RateLimitedException e) {
    // e.details() says how long the window has left.
} catch (JobTimeoutException e) {
    // Still running; e.job().id is the id to come back to.
} catch (JobFailedException e) {
    if (e.isRetryable()) {
        client.retryJob(e.job().id);
    }
}
```

`ApiException.isRetryable()` covers the HTTP cases in one call. Every failure
is refunded automatically except `insufficient_credits`, which keeps the work
already done charged; `JobFailedException.refundsCredits()` reports which.

## Paging

```java
List<Models.Job> jobs = client.allJobs(
        new Voxloom.ListOptions().status(JobStatus.COMPLETE));
```

## Units

Credits are not seconds. One second of audio costs one credit at the base rate,
multiplied by the model (`Model.multiplier()`). Everything customer-facing is
quoted in **standard minutes**, meaning minutes on Weave, so one standard
minute is 120 credits.

Use `Models.creditsToStandardMinutes` rather than dividing by 60, which reports
double.

## Development

```bash
JAVA_HOME=/path/to/jdk17 mvn test
```

Tests run against a real `HttpServer` from the JDK, so the request that goes on
the wire is what gets asserted.

## Licence

MIT.
