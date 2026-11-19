package com.spendguard.web;

import com.spendguard.config.AiProperties;
import com.spendguard.seed.PlantedAnomalyOutcome;
import com.spendguard.seed.SeedDataService;
import com.spendguard.seed.SeedRunResult;
import lombok.RequiredArgsConstructor;
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
public class AdminController {

    private static final String COST_FREE = "$0 - no API key configured, scoring falls back to rules only.";
    private static final String COST_API = "~$0.50-0.70 for a full run (~100 Claude API calls at "
            + "claude-sonnet-5-5/claude-opus-5-5 pricing).";
    private static final String COST_CLI = "No direct Anthropic API charge - uses your local Claude Code CLI "
            + "session, subject to that plan's usage limits.";

    private static final String DURATION_FREE = "~10-20 seconds.";
    private static final String DURATION_API = "~2-5 minutes (around 100 sequential calls to Claude).";
    private static final String DURATION_CLI = "~5-15 minutes (each call shells out to the CLI, slower than a "
            + "direct API call).";

    private final SeedDataService seedDataService;
    private final AiProperties aiProperties;

    /** Read-only, zero-cost: what a seed run would cost/take right now, for the pre-run screen. */
    @GetMapping("/seed-info")
    public SeedInfoResponse seedInfo() {
        boolean api = "api".equalsIgnoreCase(aiProperties.getProvider());
        boolean keyConfigured = aiProperties.getApiKey() != null && !aiProperties.getApiKey().isBlank();

        if (api && keyConfigured) {
            return new SeedInfoResponse("api", true, COST_API, DURATION_API,
                    "Most planted anomalies need the AI's agreement to actually get flagged - expect several "
                            + "\"caught: false\" results if the AI reviewer is unavailable mid-run.");
        }
        if (api) {
            return new SeedInfoResponse("api", false, COST_FREE, DURATION_FREE,
                    "Rule-only scoring: most planted anomalies will show \"caught: false\" since individual "
                            + "rule signals alone rarely clear the flag threshold - that's expected, not a bug.");
        }
        return new SeedInfoResponse("cli", false, COST_CLI, DURATION_CLI,
                "Requires the Claude Code CLI installed and authenticated on this host (not inside the app "
                        + "container) - see the README's \"Running locally\" section.");
    }

    /**
     * Demo-only: wipes and regenerates all data, on-demand so re-seeding during a demo is easy.
     *
     * <p>With {@code spendguard.ai.provider=api} and a real key configured, seeding triggers roughly
     * 100 real Claude calls (every employee's first-ever transaction with each merchant, plus the
     * planted anomalies - see {@link SeedDataService}'s class doc). That's real money spent without
     * the caller necessarily realizing it, so the first call returns a cost estimate instead of
     * running; passing {@code ?confirm=true} runs it for real. CLI provider and rule-only fallback
     * (no key configured) cost nothing, so they skip this gate entirely.
     */
    @PostMapping("/seed")
    public ResponseEntity<?> seed(@RequestParam(name = "confirm", defaultValue = "false") boolean confirm) {
        boolean wouldSpendRealCredits = "api".equalsIgnoreCase(aiProperties.getProvider())
                && aiProperties.getApiKey() != null && !aiProperties.getApiKey().isBlank();

        if (wouldSpendRealCredits && !confirm) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(new SeedCostWarningResponse(
                    "spendguard.ai.provider=api with ANTHROPIC_API_KEY set - estimated cost: " + COST_API,
                    "Re-POST with ?confirm=true to run it for real, or set spendguard.ai.provider=cli "
                            + "(or unset ANTHROPIC_API_KEY) to seed for free on rule-only scoring."));
        }

        SeedRunResult result = seedDataService.seed();
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
}
