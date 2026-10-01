package fatum.verification;

import fatum.dto.LivenessResultResponse;
import fatum.dto.LivenessSessionResponse;
import fatum.exception.FatumUserException;
import fatum.model.LivenessCheck;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.LivenessCheckStatus;
import fatum.model.constant.VerificationAttemptType;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationDecision;
import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;
import fatum.repository.LivenessCheckRepository;
import fatum.repository.UserRepository;
import fatum.service.LivenessService;
import fatum.support.Fixtures;
import fatum.verification.analyzer.FaceLivenessClient;
import fatum.verification.analyzer.LivenessResult;
import fatum.verification.analyzer.LivenessSession;
import fatum.verification.analyzer.LivenessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The proof of life is the only paid step, so the tests are mostly about the guards around it: it can
 * only be opened from a case that reached it, and it cannot be opened over and over.
 */
class LivenessSessionServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final LivenessCheckRepository livenessCheckRepository = mock(LivenessCheckRepository.class);
    private final FaceLivenessClient faceLivenessClient = mock(FaceLivenessClient.class);
    private final VerificationService verificationService = mock(VerificationService.class);
    private final LivenessService livenessService = mock(LivenessService.class);

    private final VerificationProperties properties = new VerificationProperties();
    private final User user = Fixtures.user();

    private LivenessSessionService service;
    private VerificationAttempt attempt;

    @BeforeEach
    void setUp() {
        properties.getLiveness().setBucket("fatum-liveness");
        attempt = new VerificationAttempt(
                user,
                VerificationAttemptType.FULL,
                1,
                VerificationBand.VERIFIED,
                VerificationOutcome.AWAITING_LIVENESS,
                VerificationDecision.SYSTEM,
                91d,
                95d,
                92d,
                0d,
                5d,
                "summary",
                "",
                "documents/front.png",
                null,
                null,
                "profile-images/avatar.png");

        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(livenessCheckRepository.save(any(LivenessCheck.class))).thenAnswer(call -> call.getArgument(0));
        when(livenessCheckRepository.findByUserAwsIdAndStatusOrderByCreatedAtDesc(
                eq(Fixtures.USER_ID), any())).thenReturn(List.of());

        service = new LivenessSessionService(
                userRepository,
                livenessCheckRepository,
                faceLivenessClient,
                verificationService,
                livenessService,
                properties);
    }

    // ------------------------------------------------------------------------------------------
    // Opening a session
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A session can only be opened while the case is waiting for one")
    void aSessionCanOnlyBeOpenedWhileTheCaseIsWaitingForOne() {
        when(verificationService.awaitingLiveness(Fixtures.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.LIVENESS_NOT_REQUIRED);
        verify(faceLivenessClient, never()).createSession(anyString());
    }

    @Test
    void theProofOfLifeCanBeSwitchedOffOnItsOwn() {
        properties.getLiveness().setEnabled(false);

        assertThatThrownBy(() -> service.start(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.LIVENESS_DISABLED);
    }

    @Test
    @DisplayName("A client that retried does not pay twice for the same open session")
    void anOpenSessionIsHandedBackInsteadOfSpendingAnotherOne() throws Exception {
        LivenessCheck open = new LivenessCheck(user, attempt, "session-1");
        when(verificationService.awaitingLiveness(Fixtures.USER_ID)).thenReturn(Optional.of(attempt));
        when(livenessCheckRepository.findByUserAwsIdAndStatusOrderByCreatedAtDesc(
                eq(Fixtures.USER_ID), eq(LivenessCheckStatus.CREATED))).thenReturn(List.of(open));

        LivenessSessionResponse response = service.start(Fixtures.USER_ID);

        assertThat(response.sessionId()).isEqualTo("session-1");
        assertThat(response.reused()).isTrue();
        verify(faceLivenessClient, never()).createSession(anyString());
    }

    @Test
    @DisplayName("A session that was abandoned is not reused")
    void anAbandonedSessionIsNotReused() throws Exception {
        LivenessCheck expired = new LivenessCheck(user, attempt, "session-1");
        properties.getLiveness().setSessionTtl(java.time.Duration.ZERO);
        when(verificationService.awaitingLiveness(Fixtures.USER_ID)).thenReturn(Optional.of(attempt));
        when(livenessCheckRepository.findByUserAwsIdAndStatusOrderByCreatedAtDesc(
                eq(Fixtures.USER_ID), eq(LivenessCheckStatus.CREATED))).thenReturn(List.of(expired));
        when(faceLivenessClient.createSession(Fixtures.USER_ID)).thenReturn(
                new LivenessSession("session-2", Instant.now().plusSeconds(180), "fatum-liveness", "liveness"));

        LivenessSessionResponse response = service.start(Fixtures.USER_ID);

        assertThat(response.sessionId()).isEqualTo("session-2");
        assertThat(response.reused()).isFalse();
    }

    @Test
    void anAttemptWithTooManySessionsIsRefused() {
        when(verificationService.awaitingLiveness(Fixtures.USER_ID)).thenReturn(Optional.of(attempt));
        when(livenessCheckRepository.countByAttemptId(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.start(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.LIVENESS_TOO_MANY_SESSIONS);
    }

    @Test
    void aMisconfiguredProofOfLifeIsReportedAsUnavailable() {
        when(verificationService.awaitingLiveness(Fixtures.USER_ID)).thenReturn(Optional.of(attempt));
        when(faceLivenessClient.createSession(Fixtures.USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.start(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.LIVENESS_UNAVAILABLE);
    }

    @Test
    void theSessionIsRecordedBeforeTheClientCanUseIt() throws Exception {
        when(verificationService.awaitingLiveness(Fixtures.USER_ID)).thenReturn(Optional.of(attempt));
        when(faceLivenessClient.createSession(Fixtures.USER_ID)).thenReturn(
                new LivenessSession("session-3", Instant.now().plusSeconds(180), "fatum-liveness", "liveness"));

        service.start(Fixtures.USER_ID);

        ArgumentCaptor<LivenessCheck> saved = ArgumentCaptor.forClass(LivenessCheck.class);
        verify(livenessCheckRepository).save(saved.capture());
        assertThat(saved.getValue().getSessionId()).isEqualTo("session-3");
        assertThat(saved.getValue().getStatus()).isEqualTo(LivenessCheckStatus.CREATED);
    }

    // ------------------------------------------------------------------------------------------
    // Reporting a session
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A lived proof with a reference picture verifies the identity")
    void aLivedProofVerifiesTheIdentity() throws Exception {
        LivenessCheck check = new LivenessCheck(user, attempt, "session-1");
        when(livenessCheckRepository.findBySessionId("session-1")).thenReturn(Optional.of(check));
        when(faceLivenessClient.result("session-1")).thenReturn(
                new LivenessResult(LivenessStatus.SUCCEEDED, 96d, "fatum-liveness", "liveness/session-1/reference.jpg", null));
        when(verificationService.finalizeWithLiveness(eq(user), any(), any()))
                .thenReturn(report(VerificationOutcome.VERIFIED, VerificationStatus.VERIFIED, 93d));

        LivenessResultResponse response = service.complete(Fixtures.USER_ID, "session-1");

        assertThat(response.status()).isEqualTo(LivenessStatus.SUCCEEDED);
        assertThat(response.identityVerified()).isTrue();
        assertThat(response.outcome()).isEqualTo(VerificationOutcome.VERIFIED);
        assertThat(check.getStatus()).isEqualTo(LivenessCheckStatus.SUCCEEDED);
        verify(livenessService).adoptRekognitionReference(user, "fatum-liveness", "liveness/session-1/reference.jpg");
    }

    @Test
    @DisplayName("A failed proof of life closes the attempt with no second chance")
    void aFailedProofOfLifeClosesTheAttempt() throws Exception {
        LivenessCheck check = new LivenessCheck(user, attempt, "session-1");
        when(livenessCheckRepository.findBySessionId("session-1")).thenReturn(Optional.of(check));
        when(faceLivenessClient.result("session-1")).thenReturn(
                new LivenessResult(LivenessStatus.FAILED, 8d, null, null, "spoof-detected"));
        when(verificationService.finalizeWithLiveness(eq(user), any(), any()))
                .thenReturn(report(VerificationOutcome.MANUAL_REVIEW, VerificationStatus.MANUAL_REVIEW, 0d));

        LivenessResultResponse response = service.complete(Fixtures.USER_ID, "session-1");

        assertThat(response.outcome()).isEqualTo(VerificationOutcome.MANUAL_REVIEW);
        assertThat(response.identityVerified()).isFalse();
        assertThat(check.getStatus()).isEqualTo(LivenessCheckStatus.FAILED);
        verify(livenessService, never()).adoptRekognitionReference(any(), any(), any());
    }

    @Test
    @DisplayName("A verdict that is still being computed leaves everything open")
    void aPendingVerdictLeavesTheAttemptOpen() throws Exception {
        LivenessCheck check = new LivenessCheck(user, attempt, "session-1");
        when(livenessCheckRepository.findBySessionId("session-1")).thenReturn(Optional.of(check));
        when(faceLivenessClient.result("session-1")).thenReturn(LivenessResult.pending("still processing"));

        LivenessResultResponse response = service.complete(Fixtures.USER_ID, "session-1");

        assertThat(response.status()).isEqualTo(LivenessStatus.PENDING);
        assertThat(response.outcome()).isEqualTo(VerificationOutcome.AWAITING_LIVENESS);
        assertThat(check.isOpen()).isTrue();
        verify(verificationService, never()).finalizeWithLiveness(any(), any(), any());
    }

    @Test
    @DisplayName("A technical failure leaves the case open instead of rejecting the user")
    void aTechnicalFailureLeavesTheCaseOpen() {
        LivenessCheck check = new LivenessCheck(user, attempt, "session-1");
        when(livenessCheckRepository.findBySessionId("session-1")).thenReturn(Optional.of(check));
        when(faceLivenessClient.result("session-1")).thenReturn(LivenessResult.unavailable("rekognition down"));

        assertThatThrownBy(() -> service.complete(Fixtures.USER_ID, "session-1"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.LIVENESS_UNAVAILABLE);
        assertThat(check.isOpen()).isTrue();
    }

    @Test
    @DisplayName("A proof of life without a reference picture cannot verify anybody")
    void aProofOfLifeWithoutAReferenceIsFailed() throws Exception {
        LivenessCheck check = new LivenessCheck(user, attempt, "session-1");
        when(livenessCheckRepository.findBySessionId("session-1")).thenReturn(Optional.of(check));
        when(faceLivenessClient.result("session-1")).thenReturn(
                new LivenessResult(LivenessStatus.SUCCEEDED, 96d, null, null, null));
        when(verificationService.finalizeWithLiveness(eq(user), any(), any()))
                .thenReturn(report(VerificationOutcome.MANUAL_REVIEW, VerificationStatus.MANUAL_REVIEW, 0d));

        service.complete(Fixtures.USER_ID, "session-1");

        assertThat(check.getStatus()).isEqualTo(LivenessCheckStatus.FAILED);
        verify(livenessService, never()).adoptRekognitionReference(any(), any(), any());
    }

    @Test
    void reportingTheSameSessionTwiceReturnsTheRecordedDecision() throws Exception {
        LivenessCheck check = new LivenessCheck(user, attempt, "session-1");
        check.succeed(96d, "fatum-liveness", "liveness/session-1/reference.jpg");
        attempt.resolve(VerificationBand.VERIFIED, VerificationOutcome.VERIFIED, 96d, 93d, "done", "", null);
        when(livenessCheckRepository.findBySessionId("session-1")).thenReturn(Optional.of(check));

        LivenessResultResponse response = service.complete(Fixtures.USER_ID, "session-1");

        assertThat(response.outcome()).isEqualTo(VerificationOutcome.VERIFIED);
        assertThat(response.identityVerified()).isTrue();
        verify(faceLivenessClient, never()).result(anyString());
        verify(verificationService, never()).finalizeWithLiveness(any(), any(), any());
    }

    @Test
    void anotherUserCannotReportTheSession() {
        User other = Fixtures.user("aws-user-2");
        LivenessCheck check = new LivenessCheck(other, attempt, "session-1");
        when(livenessCheckRepository.findBySessionId("session-1")).thenReturn(Optional.of(check));

        assertThatThrownBy(() -> service.complete(Fixtures.USER_ID, "session-1"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.FORBIDDEN);
    }

    @Test
    void anUnknownSessionIsRejected() {
        when(livenessCheckRepository.findBySessionId("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(Fixtures.USER_ID, "ghost"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.LIVENESS_SESSION_NOT_FOUND);
    }

    private VerificationReport report(VerificationOutcome outcome, VerificationStatus status, double referenceMatch) {
        return new VerificationReport(
                Fixtures.USER_ID,
                1,
                1,
                2,
                91d,
                95d,
                92d,
                referenceMatch,
                96d,
                5d,
                VerificationBand.VERIFIED,
                outcome,
                status,
                List.of(),
                "proof of life reported",
                Instant.now());
    }
}