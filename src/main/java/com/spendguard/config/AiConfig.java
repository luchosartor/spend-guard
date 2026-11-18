package com.spendguard.config;

import com.spendguard.ai.ApiFraudAiReviewer;
import com.spendguard.ai.CliFraudAiReviewer;
import com.spendguard.ai.FraudAiReviewer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the active {@link FraudAiReviewer} implementation based on {@code spendguard.ai.provider}. */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
@RequiredArgsConstructor
@Slf4j
public class AiConfig {

    private final AiProperties aiProperties;

    @Bean
    public FraudAiReviewer fraudAiReviewer() {
        String provider = aiProperties.getProvider();
        if ("api".equalsIgnoreCase(provider)) {
            // Checked eagerly at startup (not just lazily on the first escalated transaction) so
            // a misconfigured key is visible in the terminal before any traffic hits the API.
            if (aiProperties.getApiKey() == null || aiProperties.getApiKey().isBlank()) {
                log.warn("""
                        spendguard.ai.provider=api but ANTHROPIC_API_KEY is not set - the AI reviewer will NOT run. \
                        Every escalated transaction will fall back to rule-only scoring. Set ANTHROPIC_API_KEY, or \
                        switch spendguard.ai.provider to 'cli' to use the Claude Code CLI instead.""");
            }
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
