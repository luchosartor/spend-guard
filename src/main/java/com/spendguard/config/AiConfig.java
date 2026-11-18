package com.spendguard.config;

import com.spendguard.ai.ApiFraudAiReviewer;
import com.spendguard.ai.CliFraudAiReviewer;
import com.spendguard.ai.FraudAiReviewer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the active {@link FraudAiReviewer} implementation based on {@code spendguard.ai.provider}. */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
@RequiredArgsConstructor
public class AiConfig {

    private final AiProperties aiProperties;

    @Bean
    public FraudAiReviewer fraudAiReviewer() {
        String provider = aiProperties.getProvider();
        if ("api".equalsIgnoreCase(provider)) {
            return new ApiFraudAiReviewer(aiProperties);
        }
        if ("cli".equalsIgnoreCase(provider)) {
            return new CliFraudAiReviewer(aiProperties);
        }
        // Fail fast at startup rather than silently defaulting to "api" on a typo'd value.
        throw new IllegalStateException(
                "Unknown spendguard.ai.provider '" + provider + "' - expected 'api' or 'cli'");
    }
}
