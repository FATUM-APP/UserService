package fatum.verification;

import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationOutcome;
import fatum.verification.analyzer.LivenessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The business rules the whole verification process depends on. Each case is a sentence of the
 * requirement, written as a test.
 *
 * <p>The first phase is free and the second one is paid, so the tests are split the same way: what the
 * free checks decide, and what the proof of life decides when it answers.</p>
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

    // ------------------------------------------------------------------------------------------
    // First phase: the free checks
    // ------------------------------------------------------------------------------------------

    @Test
    void anEmptyHistoryStaysPending() {
        assertThat(policy.decideAfterFirstPhase(VerificationBand.MANUAL, 45, List.of()))
                .isEqualTo(VerificationOutcome.PENDING);
        assertThat(policy.decideAfterFirstPhase(VerificationBand.MANUAL, 45, null))
                .isEqualTo(VerificationOutcome.PENDING);
    }

    @Test
    @DisplayName("A verified band asks for the proof of life instead of verifying the user")
    void aVerifiedBandAsksForTheProofOfLife() {
        assertThat(policy.decideAfterFirstPhase(VerificationBand.VERIFIED, 88, List.of()))
                .isEqualTo(VerificationOutcome.AWAITING_LIVENESS);
        assertThat(policy.decideAfterFirstPhase(
                VerificationBand.VERIFIED,
                88,
                List.of(scored(VerificationBand.MANUAL, 45, VerificationOutcome.PENDING))))
                .isEqualTo(VerificationOutcome.AWAITING_LIVENESS);
    }

    @Test
    @DisplayName("A manual result keeps the user unverified and grants the two retries")
    void manualResultsKeepTheUserUnverifiedWhileAttemptsRemain() {
        assertThat(policy.decideAfterFirstPhase(VerificationBand.MANUAL, 45, List.of()))
                .isEqualTo(VerificationOutcome.PENDING);
        assertThat(policy.decideAfterFirstPhase(
                VerificationBand.MANUAL,
                52,
                List.of(scored(VerificationBand.MANUAL, 45, VerificationOutcome.PENDING))))
                .isEqualTo(VerificationOutcome.PENDING);
    }

    @Test
    @DisplayName("Three manual attempts escalate to an administrator")
    void threeManualAttemptsEscalateToManualReview() {
        assertThat(policy.decideAfterFirstPhase(VerificationBand.MANUAL, 60, List.of(
                scored(VerificationBand.MANUAL, 45, VerificationOutcome.PENDING),
                scored(VerificationBand.MANUAL, 52, VerificationOutcome.PENDING))))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("A rejection on the first attempt is terminal")
    void aRejectionOnTheFirstAttemptIsTerminal() {
        assertThat(policy.decideAfterFirstPhase(VerificationBand.REJECTED, 12, List.of()))
                .isEqualTo(VerificationOutcome.REJECTED);
    }

    @Test
    @DisplayName("Mixed evidence (one manual, one rejected) escalates to manual review")
    void mixedEvidenceEscalatesToManualReview() {
        assertThat(policy.decideAfterFirstPhase(
                VerificationBand.REJECTED,
                25,
                List.of(scored(VerificationBand.MANUAL, 45, VerificationOutcome.PENDING))))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("Two consecutive attempts below twenty percent reject the user outright")
    void twoConsecutiveHardRejectionsRejectTheUser() {
        assertThat(policy.decideAfterFirstPhase(VerificationBand.REJECTED, 10, List.of(
                scored(VerificationBand.MANUAL, 45, VerificationOutcome.PENDING),
                scored(VerificationBand.REJECTED, 15, VerificationOutcome.PENDING))))
                .isEqualTo(VerificationOutcome.REJECTED);
    }

    @Test
    void aSingleHardRejectionAfterAManualOneStillGoesToManualReview() {
        assertThat(policy.decideAfterFirstPhase(
                VerificationBand.REJECTED,
                15,
                List.of(scored(VerificationBand.MANUAL, 45, VerificationOutcome.PENDING))))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("A case an administrator has already closed is never reopened")
    void aClosedCaseIsNeverReopened() {
        assertThat(policy.decideAfterFirstPhase(
                VerificationBand.MANUAL,
                70,
                List.of(scored(VerificationBand.REJECTED, 10, VerificationOutcome.REJECTED))))
                .isEqualTo(VerificationOutcome.REJECTED);
        assertThat(policy.decideAfterFirstPhase(
                VerificationBand.MANUAL,
                70,
                List.of(scored(VerificationBand.MANUAL, 60, VerificationOutcome.MANUAL_REVIEW))))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    // ------------------------------------------------------------------------------------------
    // Second phase: the proof of life
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The proof of life verifies the user only when the live face owns the document")
    void theProofOfLifeVerifiesTheUser() {
        assertThat(policy.decideLiveness(LivenessStatus.SUCCEEDED, 95, true, 92))
                .isEqualTo(VerificationOutcome.VERIFIED);
    }

    @Test
    @DisplayName("A person who cannot prove to be alive goes to a human, with no second chance")
    void aFailedProofOfLifeGoesToManualReview() {
        assertThat(policy.decideLiveness(LivenessStatus.FAILED, 20, true, 90))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
        assertThat(policy.decideLiveness(LivenessStatus.EXPIRED, 0, false, 0))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("A live person who is not the owner of the document goes to a human too")
    void aLivePersonWithTheWrongDocumentGoesToManualReview() {
        assertThat(policy.decideLiveness(LivenessStatus.SUCCEEDED, 95, true, 42))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("A reference that could not be compared is not a pass")
    void aReferenceThatWasNotEvaluatedIsNotAPass() {
        assertThat(policy.decideLiveness(LivenessStatus.SUCCEEDED, 95, false, 0))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    @Test
    void aLowConfidenceProofOfLifeGoesToAManualReview() {
        assertThat(policy.decideLiveness(LivenessStatus.SUCCEEDED, 71, true, 92))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    // ------------------------------------------------------------------------------------------
    // Attempt budget
    // ------------------------------------------------------------------------------------------

    @Test
    void attemptsCannotStartOnceTheProcessIsOver() {
        assertThat(policy.canStartAttempt(List.of())).isTrue();
        assertThat(policy.canStartAttempt(List.of(scored(VerificationBand.MANUAL, 45, VerificationOutcome.PENDING))))
                .isTrue();
        assertThat(policy.canStartAttempt(List.of(
                scored(VerificationBand.MANUAL, 45, VerificationOutcome.PENDING),
                scored(VerificationBand.MANUAL, 52, VerificationOutcome.PENDING))))
                .isTrue();
        assertThat(policy.canStartAttempt(List.of(
                scored(VerificationBand.MANUAL, 45, VerificationOutcome.PENDING),
                scored(VerificationBand.MANUAL, 52, VerificationOutcome.PENDING),
                scored(VerificationBand.MANUAL, 60, VerificationOutcome.MANUAL_REVIEW))))
                .isFalse();
        assertThat(policy.canStartAttempt(List.of(scored(VerificationBand.REJECTED, 10, VerificationOutcome.REJECTED))))
                .isFalse();
        assertThat(policy.canStartAttempt(List.of(scored(VerificationBand.VERIFIED, 90, VerificationOutcome.VERIFIED))))
                .isFalse();
    }

    @Test
    @DisplayName("An attempt that is waiting for the proof of life blocks a new one")
    void anOpenLivenessBlocksANewAttempt() {
        assertThat(policy.canStartAttempt(List.of(
                scored(VerificationBand.VERIFIED, 90, VerificationOutcome.AWAITING_LIVENESS))))
                .isFalse();
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
        when(attempt.getOutcome()).thenReturn(VerificationOutcome.PENDING);

        assertThat(policy.toHistory(List.of(attempt)))
                .containsExactly(new ScoredAttempt(VerificationBand.MANUAL, 42d, VerificationOutcome.PENDING));
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
        assertThat(customPolicy.decideAfterFirstPhase(VerificationBand.MANUAL, 45, List.of()))
                .isEqualTo(VerificationOutcome.PENDING);
        assertThat(customPolicy.decideAfterFirstPhase(
                VerificationBand.MANUAL,
                50,
                List.of(scored(VerificationBand.MANUAL, 45, VerificationOutcome.PENDING))))
                .isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    private ScoredAttempt scored(VerificationBand band, double score, VerificationOutcome outcome) {
        return new ScoredAttempt(band, score, outcome);
    }
}