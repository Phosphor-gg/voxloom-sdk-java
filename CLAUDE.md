# Voxloom Java SDK

Official Java client. `java.net.http` for HTTP, Jackson for JSON, nothing else.

Java 17 (`maven.compiler.release`), because `java.net.http.HttpClient` needs 11
and records and text blocks make the tests readable.

## Layout

| File | What |
| --- | --- |
| `Voxloom.java` | The client, plus the option builders |
| `Models.java` | Response types and the unit helpers |
| `Source.java` | The tagged source union |
| `*Exception.java` | One class per failure a caller handles differently |
| Enums | `Model`, `Feature`, `ExportFormat`, `JobStatus`, `FailureKind` |

## Non-negotiables

**`Source` needs its `@JsonCreator` as well as its factory methods.** Every
`Job` carries a source, so without a deserialising constructor Jackson cannot
build a job at all and *every* response fails to parse. That shipped broken
until the tests caught it. The factories stay the public way to construct one
because they are the ones that validate.

**The JSON tag is `kind`, not `type`.** A test asserts the serialised field.

**Every response type is `@JsonIgnoreProperties(ignoreUnknown = true)`, and
the mapper disables `FAIL_ON_UNKNOWN_PROPERTIES`.** An SDK you must upgrade
before you can ignore a new field is an SDK that gets in the way.

**Unknown enum values fall back rather than throwing** for `JobStatus`,
`Model` and `FailureKind`. A newer server must not break an older client
mid-pipeline. `Feature` and `ExportFormat` do throw, because those are only
ever values this client itself sent.

**`OutOfCreditsException` (402) and `PlanTooLowException` (403) are separate
classes.** One means "buy more minutes and retry", the other means "this will
never work".

**Path segments go through the hand-rolled `encode`, not `URLEncoder`.**
`URLEncoder` is form encoding: it turns a space into `+`, which is a literal
plus in a path. A test asserts `%20`.

**Credits convert at 120 per standard minute.** Dividing by 60 reports double.

**`MIN_POLL_INTERVAL` is 2 seconds and `waitForJob` clamps up to it.** It also
*stops* rather than polling early when less than a full interval of budget is
left. A timing test covers it.

**`JobTimeoutException` carries the job.** It is still running and nothing
extra has been charged, so the caller needs the id.

**`InterruptedException` restores the interrupt flag before rethrowing.**
Swallowing it hides a cancellation from whoever is waiting on the thread.

**Omitted options are absent from the JSON, never `null`.** That is why the
request body is built as an `ObjectNode` rather than serialised from a POJO.

## Testing

```bash
JAVA_HOME=/path/to/jdk17 mvn test
```

Tests use `com.sun.net.httpserver.HttpServer` from the JDK rather than a mock
library, so the assertions are about the real request. Note the response
`Deque` cannot hold `null`: an empty body is the empty string.

## Style

- Public API is documented with Javadoc that says why, not what.
- Options are fluent builders returning `this`; the client itself is immutable.
- Fields on response types are public, because they are data. Behaviour
  (`turns()`, `title()`, `standardMinutes()`) is a method and `@JsonIgnore`d.
