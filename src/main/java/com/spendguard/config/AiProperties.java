package com.spendguard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "spendguard.ai")
public class AiProperties {

    /** "api" or "cli". */
    private String provider = "api";

    private String apiKey;

    // Non-null defaults: a null model string reaching the Anthropic/CLI request would NPE
    // (e.g. Map.of(...) rejects a null value) rather than failing in an obvious, loggable way.
    private String standardModel = "claude-haiku-5-5";

    private String premiumModel = "claude-sonnet-5-5";

    private int standardEscalationThreshold;

    private int premiumEscalationThreshold;
}
