package com.spendguard.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import com.spendguard.config.AiProperties;
import com.spendguard.detection.RuleSignals;
import com.spendguard.domain.Employee;
import com.spendguard.domain.GeoAnomalyType;
import com.spendguard.domain.Merchant;
import com.spendguard.domain.MerchantCategory;
import com.spendguard.domain.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stubs the Anthropic Messages API with a local {@code HttpServer} (JDK built-in, no new test
 * dependency) instead of mocking {@link ApiFraudAiReviewer}'s internals - this is the exact path
 * that caused two real incidents this session (a truncated-JSON parse failure under the old
 * token budget, and the request shape itself never having been exercised by a test before going
 * live). The "no text content block" case below reproduces the actual incident response shape
 * (a lone {@code thinking} block, {@code stop_reason: "max_tokens"}) verbatim.
 */
class ApiFraudAiReviewerTest {

    private HttpServer server;
    private AtomicReference<String> lastRequestBody;
    private AtomicInteger requestCount;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        lastRequestBody = new AtomicReference<>();
        requestCount = new AtomicInteger();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private URI serverUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/messages");
    }

    private void stubResponse(int status, String body) {
        server.createContext("/v1/messages", exchange -> {
            requestCount.incrementAndGet();
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private ApiFraudAiReviewer reviewerWithKey(String key) {
        AiProperties props = new AiProperties();
        props.setApiKey(key);
        return new ApiFraudAiReviewer(props, serverUri());
    }

    private ReviewContext sampleContext() {
        Employee employee = Employee.builder().id(1L).companyId(1L)
                .fullName("Jordan Lee").department("Sales").role("Account Executive").build();
        Merchant merchant = Merchant.builder().id(1L).name("Luxury Jewelers")
                .category(MerchantCategory.OFFICE).country("US").policyLimit(new BigDecimal("300.00")).build();
        Transaction transaction = Transaction.builder().id(1L).cardId(1L).merchantId(1L)
                .amount(new BigDecimal("81.08")).currency("USD")
                .occurredAt(Instant.parse("2026-11-19T12:00:00Z")).country("US").build();
        RuleSignals signals = new RuleSignals(null, false, false, GeoAnomalyType.NONE, false, 0);
        return new ReviewContext(transaction, employee, merchant, signals, "No prior history.", "claude-haiku-5-5");
    }

    @Test
    void review_withNoApiKeyConfigured_neverMakesTheHttpCall() {
        ApiFraudAiReviewer reviewer = reviewerWithKey(null);
        stubResponse(200, "{}");

        AiReviewResult result = reviewer.review(sampleContext());

        assertThat(result.aiRiskScore()).isNull();
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void review_parsesAWellFormedSuccessResponse() {
        stubResponse(200, """
                {"content": [{"type": "text", "text": "{\\"ai_risk_score\\": 72, \\"semantic_mismatch\\": true, \\"explanation\\": \\"Looks like a mismatch.\\"}"}]}
                """);

        AiReviewResult result = reviewerWithKey("sk-test").review(sampleContext());

        assertThat(result.aiRiskScore()).isEqualTo(72);
        assertThat(result.semanticMismatch()).isTrue();
        assertThat(result.explanation()).isEqualTo("Looks like a mismatch.");
    }

    @Test
    void review_sendsEffortLowAndTheConfiguredModelAndMaxTokens() {
        stubResponse(200, """
                {"content": [{"type": "text", "text": "{\\"ai_risk_score\\": 10, \\"semantic_mismatch\\": false, \\"explanation\\": \\"Fine.\\"}"}]}
                """);

        reviewerWithKey("sk-test").review(sampleContext());

        String body = lastRequestBody.get();
        assertThat(body).contains("\"model\":\"claude-haiku-5-5\"");
        assertThat(body).contains("\"max_tokens\":1024");
        assertThat(body).contains("\"effort\":\"low\"");
    }

    @Test
    void review_fallsBackGracefullyOnANonTwoXxStatus() {
        stubResponse(400, "{\"error\": \"bad request\"}");

        AiReviewResult result = reviewerWithKey("sk-test").review(sampleContext());

        assertThat(result.aiRiskScore()).isNull();
    }

    @Test
    void review_fallsBackGracefullyWhenTheResponseHasNoTextBlock() {
        // The actual incident this reproduces: thinking consumed the whole token budget, so the
        // response has only a "thinking" block and stop_reason "max_tokens" - no text at all.
        stubResponse(200, """
                {"content": [{"type": "thinking", "thinking": ""}], "stop_reason": "max_tokens"}
                """);

        AiReviewResult result = reviewerWithKey("sk-test").review(sampleContext());

        assertThat(result.aiRiskScore()).isNull();
    }

    @Test
    void review_fallsBackGracefullyOnTruncatedJsonInsideTheTextBlock() {
        // A text block that exists but was cut off mid-string before its closing quote - the
        // other half of the same real incident (some calls get a partial text block instead of
        // none at all, depending on exactly when the budget ran out).
        stubResponse(200, """
                {"content": [{"type": "text", "text": "{\\"ai_risk_score\\": 20, \\"explanation\\": \\"This is well under the lim"}]}
                """);

        AiReviewResult result = reviewerWithKey("sk-test").review(sampleContext());

        assertThat(result.aiRiskScore()).isNull();
    }
}
