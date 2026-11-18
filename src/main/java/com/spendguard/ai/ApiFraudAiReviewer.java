package com.spendguard.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.spendguard.config.AiProperties;
import lombok.extern.slf4j.Slf4j;

/** Calls the Anthropic Messages API directly over HTTP - no SDK dependency. */
@Slf4j
public class ApiFraudAiReviewer implements FraudAiReviewer {

    private static final URI MESSAGES_URI = URI.create("https://api.anthropic.com/v1/messages");
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 300;

    private final AiProperties aiProperties;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean warnedMissingKey = false;

    public ApiFraudAiReviewer(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @Override
    public AiReviewResult review(ReviewContext context) {
        String apiKey = aiProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            if (!warnedMissingKey) {
                log.warn("spendguard.ai.api-key is blank; AI review unavailable, falling back to rule-only scoring.");
                warnedMissingKey = true;
            }
            return AiReviewResult.unavailable("AI unavailable: no API key configured");
        }

        String prompt = FraudReviewPromptBuilder.build(context);
        try {
            String requestBody = mapper.writeValueAsString(Map.of(
                    "model", context.model(),
                    "max_tokens", MAX_TOKENS,
                    "messages", List.of(Map.of("role", "user", "content", prompt))));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(MESSAGES_URI)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(30)) // read timeout - connectTimeout above only covers connecting
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Anthropic API returned status {}: {}", response.statusCode(), response.body());
                return AiReviewResult.unavailable("AI unavailable: API returned status " + response.statusCode());
            }

            String text = extractFirstTextBlock(response.body());
            if (text == null) {
                log.warn("Anthropic API response had no text content block: {}", response.body());
                return AiReviewResult.unavailable("AI unavailable: no text content in response");
            }
            return AiResponseParser.parse(text);
        } catch (AiParseException e) {
            log.warn("Failed to parse Anthropic API response as the expected JSON contract", e);
            return AiReviewResult.unavailable("AI unavailable: could not parse response");
        } catch (JacksonException e) {
            // Jackson 3's ObjectMapper throws this (unchecked) for both serializing the request
            // body and parsing the response envelope - treat it the same as any other AI failure.
            log.warn("Failed to build or parse JSON for the Anthropic API call", e);
            return AiReviewResult.unavailable("AI unavailable: JSON error");
        } catch (IOException e) {
            log.warn("Anthropic API call failed", e);
            return AiReviewResult.unavailable("AI unavailable: request failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Anthropic API call interrupted", e);
            return AiReviewResult.unavailable("AI unavailable: interrupted");
        } catch (RuntimeException e) {
            // Belt-and-suspenders: the interface contract is "never throws past the reviewer" -
            // this guarantees it even for an exception type not explicitly caught above.
            log.warn("Unexpected failure calling the Anthropic API", e);
            return AiReviewResult.unavailable("AI unavailable: unexpected error");
        }
    }

    private String extractFirstTextBlock(String responseBody) {
        JsonNode root = mapper.readTree(responseBody);
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asString())) {
                return block.path("text").asString();
            }
        }
        return null;
    }
}
