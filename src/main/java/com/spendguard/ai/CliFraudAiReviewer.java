package com.spendguard.ai;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.spendguard.config.AiProperties;
import lombok.extern.slf4j.Slf4j;

/** Shells out to the `claude` CLI - same prompt and same JSON contract as {@link ApiFraudAiReviewer}. */
@Slf4j
public class CliFraudAiReviewer implements FraudAiReviewer {

    private static final long TIMEOUT_SECONDS = 30;
    private static final long STREAM_JOIN_TIMEOUT_SECONDS = 5;

    private final AiProperties aiProperties;

    public CliFraudAiReviewer(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @Override
    public AiReviewResult review(ReviewContext context) {
        String prompt = FraudReviewPromptBuilder.build(context);
        Process process = null;
        try {
            ProcessBuilder processBuilder = buildProcessBuilder(prompt, context.model());
            process = processBuilder.start();

            // Read stdout and stderr concurrently, starting immediately - NOT merged into one
            // stream (redirectErrorStream) and NOT read sequentially after waitFor(). Either of
            // those is a correctness or deadlock risk: merging let a stray stderr warning corrupt
            // the JSON we parse (the bug this replaced), and reading one stream fully before
            // starting the other risks the unread stream's OS pipe buffer filling up and blocking
            // the subprocess forever, so it must never exit and waitFor() just rides out the full
            // timeout. Draining both from the moment the process starts avoids both problems while
            // keeping the two streams separate (we parse stdout only; stderr is diagnostic).
            AtomicReference<byte[]> stdoutBytes = new AtomicReference<>();
            AtomicReference<byte[]> stderrBytes = new AtomicReference<>();
            Thread stdoutReader = startStreamReader(process.getInputStream(), stdoutBytes, "claude-cli-stdout-reader");
            Thread stderrReader = startStreamReader(process.getErrorStream(), stderrBytes, "claude-cli-stderr-reader");

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("claude CLI timed out after {}s", TIMEOUT_SECONDS);
                return AiReviewResult.unavailable("AI unavailable: CLI timed out");
            }

            stdoutReader.join(TimeUnit.SECONDS.toMillis(STREAM_JOIN_TIMEOUT_SECONDS));
            stderrReader.join(TimeUnit.SECONDS.toMillis(STREAM_JOIN_TIMEOUT_SECONDS));

            String stdout = toUtf8(stdoutBytes.get());
            String stderr = toUtf8(stderrBytes.get());
            if (!stderr.isBlank()) {
                log.debug("claude CLI wrote to stderr (exit code {}): {}", process.exitValue(), stderr.trim());
            }

            if (process.exitValue() != 0) {
                log.warn("claude CLI exited with code {}: stdout={} stderr={}", process.exitValue(), stdout, stderr);
                return AiReviewResult.unavailable("AI unavailable: CLI exited with code " + process.exitValue());
            }

            return AiResponseParser.parse(stdout.trim());
        } catch (AiParseException e) {
            log.warn("Failed to parse claude CLI output as the expected JSON contract", e);
            return AiReviewResult.unavailable("AI unavailable: could not parse response");
        } catch (IOException e) {
            log.warn("Failed to invoke claude CLI", e);
            return AiReviewResult.unavailable("AI unavailable: failed to invoke CLI");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("claude CLI invocation interrupted", e);
            return AiReviewResult.unavailable("AI unavailable: interrupted");
        } catch (RuntimeException e) {
            // Belt-and-suspenders: the interface contract is "never throws past the reviewer" -
            // this guarantees it even for an exception type not explicitly caught above.
            log.warn("Unexpected failure invoking the claude CLI", e);
            return AiReviewResult.unavailable("AI unavailable: unexpected error");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Package-visible (not private) so a test can assert the configuration directly without
     * actually starting the real {@code claude} binary.
     *
     * <p>The CLI waits up to 3s for stdin and, if nothing is piped in, prints a warning to stderr
     * and proceeds anyway. We never pipe anything in, so without redirecting stdin, that warning
     * used to corrupt the stdout we parse back when stderr was merged into it - redirecting stdin
     * from {@code /dev/null} (exactly what the CLI's own warning suggests) eliminates the warning
     * at the source instead of working around it downstream. (Unix-only; this project doesn't
     * target Windows.)
     */
    ProcessBuilder buildProcessBuilder(String prompt, String model) {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "claude", "-p", prompt, "--output-format", "text", "--model", model);
        processBuilder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        // ProcessBuilder inherits this JVM's full environment by default. If ANTHROPIC_API_KEY is
        // set (e.g. the api provider is also configured, or it leaked in from the shell that
        // launched this process), the claude CLI prefers it over the logged-in session - silently
        // billing through the API key instead of the subscription this provider exists to use.
        // The whole point of the cli provider is to work without an API key, so strip it here
        // rather than rely on every caller remembering to keep it out of this process's env.
        processBuilder.environment().remove("ANTHROPIC_API_KEY");
        processBuilder.environment().remove("ANTHROPIC_AUTH_TOKEN");
        return processBuilder;
    }

    private Thread startStreamReader(InputStream stream, AtomicReference<byte[]> target, String threadName) {
        Thread thread = new Thread(() -> {
            try {
                target.set(stream.readAllBytes());
            } catch (IOException e) {
                // The process was destroyed (timeout) or exited abruptly; nothing to capture.
                log.debug("Failed to fully read {} from claude CLI", threadName, e);
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private String toUtf8(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
