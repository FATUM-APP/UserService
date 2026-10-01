package fatum.verification;

import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The business rules the whole verification process depends on. Each case is a sentence of the
 * requirement, written as a test.
 */
class VerificationPolicyTest {

    private VerificationPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new VerificationPolicy(new VerificationProperties());
    }

    @Test
    void scoreAtOrAboveEightyIsVerified() {
        assertThat(policy.band(80, false)).isEqualTo(VerificationBand.VERIFIED);
        assertThat(policy.band(95, false)).isEqualTo(VerificationBand.VERIFIED);
    }

    @Test
    void scoreBetweenThirtyAndEightyIsManual() {
        assertThat(policy.band(30, false)).isEqualTo(VerificationBand.MANUAL);
        assertThat(policy.band(79.99, false)).isEqualTo(VerificationBand.MANUAL);
    }

    @Test
    void scoreBelowThirtyIsRejected() {
        assertThat(policy.band(29.99, false)).isEqualTo(VerificationBand.REJECTED);
        assertThat(policy.band(0, false)).isEqualTo(VerificationBand.REJECTED);
    }

    @Test
    void aForgedDocumentOrOneThatDoesNotMatchTheUserIsRejectedEvenWithAGoodScore() {
        assertThat(policy.band(95, true)).isEqualTo(VerificationBand.REJECTED);
    }

    @Test
    void anEmptyHistoryStaysPending() {
        assertThat(policy.decide(List.of())).isEqualTo(VerificationOutcome.PENDING);
        assertThat(policy.decide(null)).isEqualTo(VerificationOutcome.PENDING);
    }

    @Test
    @DisplayName("A verified band verifies the user, whatever the previous attempts were")
    void aVerifiedBandWins() {
        assertThat(policy.decide(List.of(attempt(VerificationBand.MANUAL, 45), attempt(VerificationBand.VERIFIED, 88))))
                .isEqualTo(VerificationOutcome.VERIFIED);
    }

    @Test
    @DisplayName("A manual result keeps the user unverified and grants the two retries")
    void manualResultsKeepTheUserUnverifiedWhileAttemptsRemain() {
        assertThat(policy.decide(List.of(attempt(VerificationBand.MANUAL, 45))))
                .isEqualTo(VerificationOutcome.PENDING);
        assertThat(policy.decide(List.of(attempt(VerificationBand.MANUAL, 45), attempt(VerificationBand.MANUAL, 52))))
                .isEqualTo(VerificationOutcome.PENDING);
    }

    @Test
    @DisplayName("Three manual attempts escalate to an administrator")
    void threeManualAttemptsEscalateToManualReview() {
        assertThat(policy.decide(List.of(
                attempt(VerificationBand.MANUAL, 45),
                attempt(VerificationBand.MANUAL, 52),
                attempt(VerificationBand.MANUAL, 60))))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("A rejection on the first attempt is terminal and leaves the case to an administrator")
    void aRejectionOnTheFirstAttemptIsTerminal() {
        assertThat(policy.decide(List.of(attempt(VerificationBand.REJECTED, 12))))
                .isEqualTo(VerificationOutcome.REJECTED);
    }

    @Test
    @DisplayName("Mixed evidence (one manual, one rejected) escalates to manual review")
    void mixedEvidenceEscalatesToManualReview() {
        assertThat(policy.decide(List.of(attempt(VerificationBand.MANUAL, 45), attempt(VerificationBand.REJECTED, 25))))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("Two consecutive attempts below twenty percent reject the user outright")
    void twoConsecutiveHardRejectionsRejectTheUser() {
        assertThat(policy.decide(List.of(
                attempt(VerificationBand.MANUAL, 45),
                attempt(VerificationBand.REJECTED, 15),
                attempt(VerificationBand.REJECTED, 10))))
                .isEqualTo(VerificationOutcome.REJECTED);
    }

    @Test
    void aSingleHardRejectionAfterAManualOneStillGoesToManualReview() {
        assertThat(policy.decide(List.of(
                attempt(VerificationBand.MANUAL, 45),
                attempt(VerificationBand.REJECTED, 15))))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    @Test
    void attemptsCannotStartOnceTheProcessIsOver() {
        assertThat(policy.canStartAttempt(List.of())).isTrue();
        assertThat(policy.canStartAttempt(List.of(attempt(VerificationBand.MANUAL, 45)))).isTrue();
        assertThat(policy.canStartAttempt(List.of(
                attempt(VerificationBand.MANUAL, 45),
                attempt(VerificationBand.MANUAL, 52)))).isTrue();
        assertThat(policy.canStartAttempt(List.of(
                attempt(VerificationBand.MANUAL, 45),
                attempt(VerificationBand.MANUAL, 52),
                attempt(VerificationBand.MANUAL, 60)))).isFalse();
        assertThat(policy.canStartAttempt(List.of(attempt(VerificationBand.REJECTED, 10)))).isFalse();
        assertThat(policy.canStartAttempt(List.of(attempt(VerificationBand.VERIFIED, 90)))).isFalse();
    }

    @Test
    void countsTheRemainingAttempts() {
        assertThat(policy.remainingAttempts(0)).isEqualTo(3);
        assertThat(policy.remainingAttempts(1)).isEqualTo(2);
        assertThat(policy.remainingAttempts(3)).isZero();
        assertThat(policy.remainingAttempts(7)).isZero();
    }

    @Test
    void mapsThePersistedAttemptsToThePolicyView() {
        VerificationAttempt attempt = mock(VerificationAttempt.class);
        when(attempt.getBand()).thenReturn(VerificationBand.MANUAL);
        when(attempt.getScore()).thenReturn(42d);

        assertThat(policy.toHistory(List.of(attempt)))
                .containsExactly(new ScoredAttempt(VerificationBand.MANUAL, 42d));
    }

    @Test
    void theThresholdsComeFromTheConfiguration() {
        VerificationProperties custom = new VerificationProperties();
        custom.setVerifiedThreshold(70);
        custom.setManualThreshold(40);
        custom.setMaxAttempts(2);
        VerificationPolicy customPolicy = new VerificationPolicy(custom);

        assertThat(customPolicy.band(70, false)).isEqualTo(VerificationBand.VERIFIED);
        assertThat(customPolicy.band(45, false)).isEqualTo(VerificationBand.MANUAL);
        assertThat(customPolicy.decide(List.of(attempt(VerificationBand.MANUAL, 45))))
                .isEqualTo(VerificationOutcome.PENDING);
        assertThat(customPolicy.decide(List.of(attempt(VerificationBand.MANUAL, 45), attempt(VerificationBand.MANUAL, 50))))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    private ScoredAttempt attempt(VerificationBand band, double score) {
        return new ScoredAttempt(band, score);
    }
}
