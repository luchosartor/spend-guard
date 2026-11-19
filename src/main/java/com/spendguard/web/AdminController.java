package com.spendguard.web;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.spendguard.ai.AiMode;
import com.spendguard.config.AiProperties;
import com.spendguard.seed.PlantedAnomalyOutcome;
import com.spendguard.seed.SeedDataService;
import com.spendguard.seed.SeedRunResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private static final String COST_FREE = "$0 - rule-only scoring, no AI call made.";
    // SeedDataService now plants 3 types on 1 employee per company (30 ingest calls total) instead
    // of 6 types across 8 employees - at most ~10 of those ever escalate to the AI (2 first-time-
    // merchant calls from history + 3 plants, x2 companies), not ~100. Provisional: not yet
    // re-measured against a real run on this trimmed-down generator.
    private static final String COST_API = "~$0.02-0.05 for a full run (up to ~10 Claude API calls at "
            + "claude-haiku-5-5/claude-sonnet-5-5 pricing, each including its own reasoning step) - "
            + "provisional, not yet measured.";
    private static final String COST_CLI = "No direct Anthropic API charge - uses the local Claude Code CLI, "
            + "subject to that plan's usage limits.";

    private static final String DURATION_FREE = "~5-10 seconds.";
    private static final String DURATION_API = "~30-90 seconds (up to ~10 sequential calls to Claude, each "
            + "including its own reasoning step) - provisional, not yet measured.";
    private static final String DURATION_CLI = "~1-3 minutes (up to ~10 calls, each shelling out to the CLI - "
            + "slower per call than a direct API call).";

    private final SeedDataService seedDataService;
    private final AiProperties aiProperties;

    /**
     * Read-only, zero-cost: availability and cost/time estimate for each AI mode, so the frontend
     * can render all three options (and grey out ones that can't run) without picking one first.
     */
    @GetMapping("/seed-info")
    public SeedInfoResponse seedInfo() {
        boolean keyConfigured = aiProperties.getApiKey() != null && !aiProperties.getApiKey().isBlank();
        SeedOptionResponse api = keyConfigured
                ? new SeedOptionResponse(true, null, COST_API, DURATION_API)
                : new SeedOptionResponse(false, "ANTHROPIC_API_KEY is not set on this server.", COST_API, DURATION_API);

        boolean cliAvailable = isCliAvailable();
        SeedOptionResponse cli = cliAvailable
                ? new SeedOptionResponse(true, null, COST_CLI, DURATION_CLI)
                : new SeedOptionResponse(false, "The `claude` CLI was not found on this host (only works "
                        + "running the app directly, not inside the Docker container).", COST_CLI, DURATION_CLI);

        SeedOptionResponse none = new SeedOptionResponse(true, null, COST_FREE, DURATION_FREE);

        return new SeedInfoResponse(api, cli, none);
    }

    /**
     * Demo-only: wipes and regenerates all data, on-demand so re-seeding during a demo is easy.
     *
     * <p>{@code mode} picks which {@link com.spendguard.ai.FraudAiReviewer} every escalation in
     * this run uses - independent of {@code spendguard.ai.provider}, so switching between API, CLI
     * and rule-only for a test run never requires restarting the app. Defaults to {@code none}.
     *
     * <p>{@code mode=api} triggers roughly 100 real Claude calls (see {@link SeedDataService}'s
     * class doc) - real money spent without the caller necessarily realizing it - so the first call
     * with a configured key returns a cost estimate instead of running; {@code ?confirm=true} runs
     * it for real. {@code mode=cli} and {@code mode=none} skip this gate entirely since they're free.
     */
    @PostMapping("/seed")
    public ResponseEntity<?> seed(
            @RequestParam(name = "mode", defaultValue = "NONE") String modeParam,
            @RequestParam(name = "confirm", defaultValue = "false") boolean confirm) {
        AiMode mode = parseMode(modeParam);
        boolean keyConfigured = aiProperties.getApiKey() != null && !aiProperties.getApiKey().isBlank();

        if (mode == AiMode.API && !keyConfigured) {
            throw new IllegalArgumentException("mode=api requires ANTHROPIC_API_KEY to be set on this server - "
                    + "use mode=cli or mode=none instead, or set the key and restart.");
        }
        if (mode == AiMode.API && !confirm) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(new SeedCostWarningResponse(
                    "mode=api with ANTHROPIC_API_KEY set - estimated cost: " + COST_API,
                    "Re-POST with ?mode=api&confirm=true to run it for real, or use mode=cli / mode=none to seed for free."));
        }

        SeedRunResult result = seedDataService.seed(mode);
        return ResponseEntity.ok(new AdminSeedResponse(
                result.companiesCreated(),
                result.employeesCreated(),
                result.merchantsCreated(),
                result.totalTransactions(),
                result.totalFlags(),
                result.plantedAnomalies().stream()
                        .map(a -> new PlantedAnomalyResult(a.transactionId(), a.plantedType(), a.companyPlan(), a.caught()))
                        .toList()));
    }

    private AiMode parseMode(String modeParam) {
        try {
            return AiMode.valueOf(modeParam.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be one of api, cli, none - got '" + modeParam + "'");
        }
    }

    /** Zero-cost: just checks the binary is on PATH, never invokes it. Unix-only, same as {@link com.spendguard.ai.CliFraudAiReviewer}. */
    private boolean isCliAvailable() {
        try {
            Process process = new ProcessBuilder("which", "claude").redirectErrorStream(true).start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Could not check for the claude CLI on PATH", e);
            return false;
        }
    }
}
