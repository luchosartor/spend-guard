package com.spendguard.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiResponseParserTest {

    @Test
    void parsesValidRawJson() throws AiParseException {
        String raw = "{\"ai_risk_score\": 72, \"semantic_mismatch\": true, \"explanation\": \"Looks risky.\"}";

        AiReviewResult result = AiResponseParser.parse(raw);

        assertThat(result.aiRiskScore()).isEqualTo(72);
        assertThat(result.semanticMismatch()).isTrue();
        assertThat(result.explanation()).isEqualTo("Looks risky.");
    }

    @Test
    void parsesJsonFencedInMarkdownCodeBlock() throws AiParseException {
        String raw = "```json\n{\"ai_risk_score\": 40, \"semantic_mismatch\": false, \"explanation\": \"Seems fine.\"}\n```";

        AiReviewResult result = AiResponseParser.parse(raw);

        assertThat(result.aiRiskScore()).isEqualTo(40);
        assertThat(result.semanticMismatch()).isFalse();
        assertThat(result.explanation()).isEqualTo("Seems fine.");
    }

    @Test
    void parsesJsonFencedWithoutLanguageTag() throws AiParseException {
        String raw = "```\n{\"ai_risk_score\": 10, \"semantic_mismatch\": false, \"explanation\": \"Fine.\"}\n```";

        AiReviewResult result = AiResponseParser.parse(raw);

        assertThat(result.aiRiskScore()).isEqualTo(10);
    }

    @Test
    void clampsOutOfRangeScoreInto0To100() throws AiParseException {
        String raw = "{\"ai_risk_score\": 150, \"semantic_mismatch\": false, \"explanation\": \"Over.\"}";

        AiReviewResult result = AiResponseParser.parse(raw);

        assertThat(result.aiRiskScore()).isEqualTo(100);
    }

    @Test
    void throwsOnMalformedJson() {
        String raw = "this is not json at all {{{";

        assertThatThrownBy(() -> AiResponseParser.parse(raw)).isInstanceOf(AiParseException.class);
    }

    @Test
    void throwsOnJsonMissingRequiredFields() {
        String raw = "{\"ai_risk_score\": 50}";

        assertThatThrownBy(() -> AiResponseParser.parse(raw)).isInstanceOf(AiParseException.class);
    }

    @Test
    void throwsOnBlankResponse() {
        assertThatThrownBy(() -> AiResponseParser.parse("   ")).isInstanceOf(AiParseException.class);
    }

    @Test
    void throwsOnProseWrappedAroundJson() {
        String raw = "Sure, here's the result: {\"ai_risk_score\": 50, \"semantic_mismatch\": false, \"explanation\": \"x\"}";

        assertThatThrownBy(() -> AiResponseParser.parse(raw)).isInstanceOf(AiParseException.class);
    }

    @Test
    void throwsOnCliStdinWarningPrependedToOtherwiseValidJson() {
        // Real incident: before CliFraudAiReviewer redirected stdin from /dev/null, the `claude`
        // CLI's "no stdin data received" warning landed on the same stream as the JSON (stderr was
        // merged into stdout), so the parser saw this exact shape on every real invocation despite
        // the model's JSON itself being perfectly valid.
        String raw = "Warning: no stdin data received in 3s, proceeding without it. If piping from a "
                + "slow command, redirect stdin explicitly: < /dev/null to skip, or wait longer.\n"
                + "{\"ai_risk_score\": 18, \"semantic_mismatch\": false, \"explanation\": \"Looks routine.\"}";

        assertThatThrownBy(() -> AiResponseParser.parse(raw)).isInstanceOf(AiParseException.class);
    }

    @Test
    void throwsOnWrongTypedScoreInsteadOfCoercing() {
        String raw = "{\"ai_risk_score\": \"high\", \"semantic_mismatch\": false, \"explanation\": \"x\"}";

        assertThatThrownBy(() -> AiResponseParser.parse(raw)).isInstanceOf(AiParseException.class);
    }

    @Test
    void throwsOnNullScoreInsteadOfCoercingToZero() {
        String raw = "{\"ai_risk_score\": null, \"semantic_mismatch\": false, \"explanation\": \"x\"}";

        assertThatThrownBy(() -> AiResponseParser.parse(raw)).isInstanceOf(AiParseException.class);
    }

    @Test
    void throwsOnWrongTypedMismatchFlag() {
        String raw = "{\"ai_risk_score\": 50, \"semantic_mismatch\": \"yes\", \"explanation\": \"x\"}";

        assertThatThrownBy(() -> AiResponseParser.parse(raw)).isInstanceOf(AiParseException.class);
    }

    @Test
    void throwsOnWrongTypedExplanation() {
        String raw = "{\"ai_risk_score\": 50, \"semantic_mismatch\": false, \"explanation\": 12345}";

        assertThatThrownBy(() -> AiResponseParser.parse(raw)).isInstanceOf(AiParseException.class);
    }
}
