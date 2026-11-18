package com.spendguard.web;

import com.spendguard.config.AiProperties;
import com.spendguard.seed.PlantedAnomalyOutcome;
import com.spendguard.seed.SeedDataService;
import com.spendguard.seed.SeedRunResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SeedDataService seedDataService;
    private final AiProperties aiProperties;

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
                    "spendguard.ai.provider=api with ANTHROPIC_API_KEY set: seeding makes ~100 real "
                            + "calls to Claude (one per employee's first-ever transaction with each merchant, "
                            + "plus the planted anomalies) at claude-sonnet-5-5/claude-opus-5-5 pricing - "
                            + "estimated cost ~$0.50-0.70 for a full run.",
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
