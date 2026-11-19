package com.spendguard.web;

import com.spendguard.ai.AiMode;
import com.spendguard.config.AiProperties;
import com.spendguard.seed.SeedDataService;
import com.spendguard.seed.SeedRunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the real cost-gate logic the seed-info/seed endpoints exist for - these are pure
 * constructor-injected classes, so plain Mockito is enough; no Spring context needed. The CLI
 * availability check shells out to {@code which claude} directly, so its boolean isn't asserted
 * here (it legitimately differs machine to machine) - only that its presence/reason are
 * consistent with each other.
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private SeedDataService seedDataService;

    private AiProperties aiProperties;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        controller = new AdminController(seedDataService, aiProperties);
    }

    @Test
    void seedInfo_apiUnavailableWithoutAKey() {
        aiProperties.setApiKey(null);

        SeedInfoResponse info = controller.seedInfo();

        assertThat(info.api().available()).isFalse();
        assertThat(info.api().unavailableReason()).contains("ANTHROPIC_API_KEY");
        assertThat(info.none().available()).isTrue();
        assertThat(info.none().unavailableReason()).isNull();
    }

    @Test
    void seedInfo_apiAvailableWithAKeyConfigured() {
        aiProperties.setApiKey("sk-test-key");

        SeedInfoResponse info = controller.seedInfo();

        assertThat(info.api().available()).isTrue();
        assertThat(info.api().unavailableReason()).isNull();
    }

    @Test
    void seedInfo_cliReasonIsConsistentWithItsAvailableFlag() {
        SeedInfoResponse info = controller.seedInfo();

        if (info.cli().available()) {
            assertThat(info.cli().unavailableReason()).isNull();
        } else {
            assertThat(info.cli().unavailableReason()).isNotBlank();
        }
    }

    @Test
    void seed_modeApiWithoutAKeyIsRejected() {
        aiProperties.setApiKey(null);

        assertThatThrownBy(() -> controller.seed("api", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode=api");

        verify(seedDataService, never()).seed(any());
    }

    @Test
    void seed_modeApiWithAKeyButNoConfirmReturnsCostWarningInsteadOfRunning() {
        aiProperties.setApiKey("sk-test-key");

        ResponseEntity<?> response = controller.seed("api", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(response.getBody()).isInstanceOf(SeedCostWarningResponse.class);
        verify(seedDataService, never()).seed(any());
    }

    @Test
    void seed_modeApiWithAKeyAndConfirmActuallyRuns() {
        aiProperties.setApiKey("sk-test-key");
        when(seedDataService.seed(AiMode.API))
                .thenReturn(new SeedRunResult(2, 2, 3, 30, 6, List.of()));

        ResponseEntity<?> response = controller.seed("api", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(AdminSeedResponse.class);
        verify(seedDataService).seed(AiMode.API);
    }

    @Test
    void seed_modeNoneRunsImmediatelyRegardlessOfConfirmOrKey() {
        aiProperties.setApiKey(null);
        when(seedDataService.seed(AiMode.NONE))
                .thenReturn(new SeedRunResult(2, 2, 3, 30, 6, List.of()));

        ResponseEntity<?> response = controller.seed("none", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(seedDataService).seed(AiMode.NONE);
    }

    @Test
    void seed_modeIsCaseInsensitive() {
        aiProperties.setApiKey(null);
        when(seedDataService.seed(AiMode.NONE))
                .thenReturn(new SeedRunResult(2, 2, 3, 30, 6, List.of()));

        ResponseEntity<?> response = controller.seed("NoNe", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void seed_unknownModeIsRejected() {
        assertThatThrownBy(() -> controller.seed("bogus", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");

        verify(seedDataService, never()).seed(any());
    }
}
