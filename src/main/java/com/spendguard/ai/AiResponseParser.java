package com.spendguard.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses the raw text both {@link ApiFraudAiReviewer} and {@link CliFraudAiReviewer} expect back from
 * Claude: a single JSON object {@code {"ai_risk_score": <0-100 int>, "semantic_mismatch": <bool>,
 * "explanation": "<text>"}}, with no prose and no markdown fences - though fences are stripped
 * defensively in case the model adds them anyway.
 */
public final class AiResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiResponseParser() {
    }

    public static AiReviewResult parse(String rawText) throws AiParseException {
        if (rawText == null || rawText.isBlank()) {
            throw new AiParseException("Empty AI response");
        }
        String cleaned = stripMarkdownFences(rawText.trim());
        JsonNode node;
        try {
            node = MAPPER.readTree(cleaned);
        } catch (Exception e) {
            throw new AiParseException("AI response is not valid JSON: " + rawText, e);
        }
        if (!node.has("ai_risk_score") || !node.has("semantic_mismatch") || !node.has("explanation")) {
            throw new AiParseException("AI response is missing required fields: " + rawText);
        }

        JsonNode scoreNode = node.get("ai_risk_score");
        JsonNode mismatchNode = node.get("semantic_mismatch");
        JsonNode explanationNode = node.get("explanation");

        // Check the actual JSON type before calling asInt()/asBoolean()/asString(): Jackson 3
        // throws (JsonNodeException) rather than coercing on a type mismatch, e.g.
        // "ai_risk_score": "high" or "ai_risk_score": null - both must become a parse failure
        // (-> AI unavailable upstream), never a silently-coerced 0/false/"null".
        if (!scoreNode.isNumber()) {
            throw new AiParseException("ai_risk_score is not a number: " + rawText);
        }
        if (!mismatchNode.isBoolean()) {
            throw new AiParseException("semantic_mismatch is not a boolean: " + rawText);
        }
        if (!explanationNode.isString()) {
            throw new AiParseException("explanation is not a string: " + rawText);
        }

        int score = scoreNode.asInt();
        boolean semanticMismatch = mismatchNode.asBoolean();
        String explanation = explanationNode.asString();
        int clampedScore = Math.max(0, Math.min(100, score));
        return new AiReviewResult(clampedScore, semanticMismatch, explanation);
    }

    private static String stripMarkdownFences(String text) {
        String result = text;
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            result = firstNewline >= 0 ? result.substring(firstNewline + 1) : result.substring(3);
        }
        if (result.endsWith("```")) {
            result = result.substring(0, result.length() - 3);
        }
        return result.trim();
    }
}
