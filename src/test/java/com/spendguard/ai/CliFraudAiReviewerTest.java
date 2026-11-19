package com.spendguard.ai;

import java.io.File;

import com.spendguard.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the {@link ProcessBuilder} configuration directly, without starting the real
 * {@code claude} binary (that's inherently slow/flaky for a unit test suite - see the real
 * incident referenced in {@link CliFraudAiReviewer#buildProcessBuilder}).
 */
class CliFraudAiReviewerTest {

    private final CliFraudAiReviewer reviewer = new CliFraudAiReviewer(new AiProperties());

    @Test
    void redirectsStdinFromDevNullSoTheCliNeverWarnsOnStdout() {
        ProcessBuilder processBuilder = reviewer.buildProcessBuilder("some prompt", "some-model");

        assertThat(processBuilder.redirectInput().file()).isEqualTo(new File("/dev/null"));
    }

    @Test
    void doesNotMergeStderrIntoStdout() {
        // redirectErrorStream(true) was the round-3 "fix" that actually caused the real bug: it
        // let the CLI's stdin warning (written to stderr) land on the same stream as the JSON we
        // parse. Stderr must stay separate; CliFraudAiReviewer drains it on its own thread instead.
        ProcessBuilder processBuilder = reviewer.buildProcessBuilder("some prompt", "some-model");

        assertThat(processBuilder.redirectErrorStream()).isFalse();
    }

    @Test
    void passesThePromptAndModelThrough() {
        ProcessBuilder processBuilder = reviewer.buildProcessBuilder("the prompt text", "claude-haiku-5-5");

        assertThat(processBuilder.command()).containsSequence("-p", "the prompt text");
        assertThat(processBuilder.command()).containsSequence("--model", "claude-haiku-5-5");
    }

    @Test
    void neverLetsTheCliSeeAnAnthropicApiKey() {
        // ProcessBuilder inherits this JVM's environment by default - if ANTHROPIC_API_KEY is set
        // (e.g. for the api provider, or leaked in from the launching shell), the claude CLI
        // prefers it over the logged-in session, silently billing the API key instead of the
        // subscription the cli provider exists to use instead.
        ProcessBuilder processBuilder = reviewer.buildProcessBuilder("some prompt", "some-model");

        assertThat(processBuilder.environment()).doesNotContainKey("ANTHROPIC_API_KEY");
        assertThat(processBuilder.environment()).doesNotContainKey("ANTHROPIC_AUTH_TOKEN");
    }
}
