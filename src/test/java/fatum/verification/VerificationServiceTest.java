package fatum.verification;

import fatum.exception.FatumUserException;
import fatum.model.DocumentFile;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationAttemptType;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationDecision;
import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;
import fatum.repository.DocumentFileRepository;
import fatum.repository.LivenessCheckRepository;
import fatum.repository.ProfileImageRepository;
import fatum.repository.UserRepository;
import fatum.repository.VerificationAttemptRepository;
import fatum.service.CognitoGroupService;
import fatum.storage.FileStorageProperties;
import fatum.support.Fixtures;
import fatum.verification.analyzer.DocumentAnalyzer;
import fatum.verification.analyzer.DocumentFieldMatcher;
import fatum.verification.analyzer.ExtractedDocument;
import fatum.verification.analyzer.FaceComparator;
import fatum.verification.analyzer.FaceMatch;
import fatum.verification.analyzer.FraudAnalyzer;
import fatum.verification.analyzer.FraudAssessment;
import fatum.verification.analyzer.LivenessResult;
import fatum.verification.analyzer.LivenessStatus;
import fatum.verification.analyzer.VerificationScoreCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The identity verification runs in two phases: the free checks and the paid proof of life. The tests
 * follow the same split, because the money question is exactly whether the paid step can be reached
 * from anywhere else.
 */
class VerificationServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final DocumentFileRepository documentFileRepository = mock(DocumentFileRepository.class);
    private final LivenessCheckRepository livenessCheckRepository = mock(LivenessCheckRepository.class);
    private final ProfileImageRepository profileImageRepository = mock(ProfileImageRepository.class);
    private final VerificationAttemptRepository attemptRepository = mock(VerificationAttemptRepository.class);
    private final DocumentAnalyzer documentAnalyzer = mock(DocumentAnalyzer.class);
    private final DocumentFieldMatcher fieldMatcher = mock(DocumentFieldMatcher.class);
    private final FaceComparator faceComparator = mock(FaceComparator.class);
    private final FraudAnalyzer fraudAnalyzer = mock(FraudAnalyzer.class);
    private final VerificationRetentionService retentionService = mock(VerificationRetentionService.class);
    private final CognitoGroupService cognitoGroupService = mock(CognitoGroupService.class);
    private final FileContentFetcher fileContentFetcher = mock(FileContentFetcher.class);

    private final VerificationProperties properties = new VerificationProperties();

    private User user;
    private DocumentFile document;
    private ProfileImage profileImage;

    private VerificationService service;

    @BeforeEach
    void setUp() {
        user = Fixtures.user();
        document = Fixtures.document(user);
        profileImage = Fixtures.profileImage(user);

        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(document));
        when(profileImageRepository.findActive(Fixtures.USER_ID)).thenReturn(Optional.of(profileImage));
        when(profileImageRepository.findByUserAwsIdAndStatus(any(), any())).thenReturn(Optional.empty());
        when(attemptRepository.findByUserAwsIdAndTypeOrderByAttemptNumberAsc(
                Fixtures.USER_ID, VerificationAttemptType.FULL)).thenReturn(List.of());
        when(attemptRepository.save(any(VerificationAttempt.class))).thenAnswer(call -> call.getArgument(0));
        when(fileContentFetcher.fetch(anyString(), anyString()))
                .thenReturn(new byte[]{1}, new byte[]{2}, new byte[]{3});
        when(documentAnalyzer.extract(any(), any(), any())).thenReturn(extractedDocument());
        when(fieldMatcher.match(any(), any()))
                .thenReturn(new DocumentFieldMatcher.MatchResult(true, 100d, List.of(), List.of()));

        service = new VerificationService(
                userRepository,
                documentFileRepository,
                livenessCheckRepository,
                profileImageRepository,
                attemptRepository,
                documentAnalyzer,
                fieldMatcher,
                faceComparator,
                fraudAnalyzer,
                new VerificationScoreCalculator(),
                new VerificationPolicy(properties),
                properties,
                retentionService,
                cognitoGroupService,
                fileContentFetcher,
                new FileStorageProperties());
    }

    // ------------------------------------------------------------------------------------------
    // First phase: the free checks
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Everything matching asks for the proof of life instead of verifying the user")
    void aHighScoreAsksForTheProofOfLife() throws Exception {
        givenDocumentProfileMatch(95d);
        givenFraud(FraudAssessment.of(5d, List.of(), "clean"));

        VerificationReport report = service.submit(Fixtures.USER_ID);

        assertThat(report.band()).isEqualTo(VerificationBand.VERIFIED);
        assertThat(report.outcome()).isEqualTo(VerificationOutcome.AWAITING_LIVENESS);
        assertThat(report.userStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(report.needsLiveness()).isTrue();
        assertThat(report.score()).isGreaterThan(90d);
        assertThat(report.attemptNumber()).isEqualTo(1);
        assertThat(report.attemptsRemaining()).isEqualTo(2);
        assertThat(user.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        verify(retentionService).apply(VerificationOutcome.AWAITING_LIVENESS, Fixtures.USER_ID);
        verify(cognitoGroupService, never()).grantVerified(anyString());
    }

    @Test
    @DisplayName("An attempt waiting for the proof of life keeps every piece of evidence")
    void theEvidenceIsKeptWhileTheProofOfLifeIsPending() throws Exception {
        givenDocumentProfileMatch(95d);
        givenFraud(FraudAssessment.of(5d, List.of(), "clean"));

        service.submit(Fixtures.USER_ID);

        ArgumentCaptor<VerificationAttempt> saved = ArgumentCaptor.forClass(VerificationAttempt.class);
        verify(attemptRepository).save(saved.capture());
        VerificationAttempt attempt = saved.getValue();
        assertThat(attempt.getType()).isEqualTo(VerificationAttemptType.FULL);
        assertThat(attempt.getDocumentFrontKey()).isEqualTo(document.getFrontKey());
        assertThat(attempt.getProfileImageKey()).isEqualTo(profileImage.getImageKey());
        assertThat(attempt.getLivenessKey()).isNull();
        assertThat(attempt.getSummary()).contains("Attempt 1/3");
    }

    @Test
    void aManualScoreKeepsTheUserUnverifiedAndWipesTheEvidence() throws Exception {
        givenDocumentProfileMatch(50d);
        givenFraud(FraudAssessment.of(50d, List.of("low-quality"), "unclear"));
        when(fieldMatcher.match(any(), any()))
                .thenReturn(new DocumentFieldMatcher.MatchResult(true, 40d, List.of(), List.of("fullName")));

        VerificationReport report = service.submit(Fixtures.USER_ID);

        assertThat(report.band()).isEqualTo(VerificationBand.MANUAL);
        assertThat(report.outcome()).isEqualTo(VerificationOutcome.PENDING);
        assertThat(report.userStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(report.flags()).contains("mismatch:fullName");
        assertThat(user.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        verify(retentionService).apply(VerificationOutcome.PENDING, Fixtures.USER_ID);
        verify(cognitoGroupService, never()).grantVerified(anyString());
    }

    @Test
    void theThirdManualAttemptEscalatesToAnAdministrator() throws Exception {
        givenDocumentProfileMatch(50d);
        givenFraud(FraudAssessment.of(50d, List.of(), "unclear"));
        when(fieldMatcher.match(any(), any()))
                .thenReturn(new DocumentFieldMatcher.MatchResult(true, 40d, List.of(), List.of()));
        when(attemptRepository.findByUserAwsIdAndTypeOrderByAttemptNumberAsc(
                Fixtures.USER_ID, VerificationAttemptType.FULL))
                .thenReturn(List.of(
                        attempt(1, VerificationBand.MANUAL, 46.5),
                        attempt(2, VerificationBand.MANUAL, 47.5)));

        VerificationReport report = service.submit(Fixtures.USER_ID);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.MANUAL_REVIEW);
        assertThat(report.userStatus()).isEqualTo(VerificationStatus.MANUAL_REVIEW);
        assertThat(report.attemptNumber()).isEqualTo(3);
        verify(retentionService).apply(VerificationOutcome.MANUAL_REVIEW, Fixtures.USER_ID);
    }

    @Test
    void aLowScoreRejectsTheUserOnTheFirstAttempt() throws Exception {
        givenDocumentProfileMatch(15d);
        givenFraud(FraudAssessment.of(80d, List.of("inconsistent"), "looks manipulated"));
        when(fieldMatcher.match(any(), any()))
                .thenReturn(new DocumentFieldMatcher.MatchResult(true, 10d, List.of(), List.of("documentNumber")));

        VerificationReport report = service.submit(Fixtures.USER_ID);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.REJECTED);
        assertThat(report.userStatus()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(report.flags()).contains("forged-document");
        verify(retentionService).apply(VerificationOutcome.REJECTED, Fixtures.USER_ID);
    }

    @Test
    void aForgedDocumentIsRejectedEvenWithAPerfectScore() throws Exception {
        givenDocumentProfileMatch(99d);
        givenFraud(FraudAssessment.of(90d, List.of("altered-number"), "the number does not exist"));

        VerificationReport report = service.submit(Fixtures.USER_ID);

        assertThat(report.score()).isGreaterThan(70d);
        assertThat(report.band()).isEqualTo(VerificationBand.REJECTED);
        assertThat(report.outcome()).isEqualTo(VerificationOutcome.REJECTED);
        assertThat(report.flags()).contains("fraud:altered-number", "forged-document");
    }

    @Test
    @DisplayName("A document that does not show the person of the profile picture is rejected before paying")
    void aDocumentThatDoesNotMatchThePictureIsRejected() throws Exception {
        givenDocumentProfileMatch(5d);
        givenFraud(FraudAssessment.of(0d, List.of(), "clean"));

        VerificationReport report = service.submit(Fixtures.USER_ID);

        assertThat(report.band()).isEqualTo(VerificationBand.REJECTED);
        assertThat(report.flags()).contains("document-does-not-match-profile-picture");
    }

    @Test
    void anAlreadyVerifiedUserCannotSubmitAgain() {
        user.markVerificationStatus(VerificationStatus.VERIFIED);

        assertThatThrownBy(() -> service.submit(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.VERIFICATION_ALREADY_COMPLETED);
    }

    @Test
    void aCaseWaitingForAnAdministratorCannotBeSubmittedAgain() {
        user.markVerificationStatus(VerificationStatus.MANUAL_REVIEW);

        assertThatThrownBy(() -> service.submit(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NO_ATTEMPTS_LEFT);
    }

    @Test
    void aUserWithoutAttemptsLeftCannotSubmit() {
        when(attemptRepository.findByUserAwsIdAndTypeOrderByAttemptNumberAsc(
                Fixtures.USER_ID, VerificationAttemptType.FULL))
                .thenReturn(List.of(attempt(1, VerificationBand.REJECTED, 10d, VerificationOutcome.REJECTED)));

        assertThatThrownBy(() -> service.submit(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NO_ATTEMPTS_LEFT);
    }

    @Test
    @DisplayName("The proof of life is never requested from a phase that did not pass")
    void anIncompleteFirstPhaseDoesNotReachTheProofOfLife() throws Exception {
        givenDocumentProfileMatch(95d);
        givenFraud(FraudAssessment.of(50d, List.of(), "unclear"));
        when(fieldMatcher.match(any(), any()))
                .thenReturn(new DocumentFieldMatcher.MatchResult(true, 40d, List.of(), List.of()));

        assertThat(service.submit(Fixtures.USER_ID).outcome()).isEqualTo(VerificationOutcome.PENDING);
    }

    @Test
    void theIdentityCannotBeVerifiedWithoutTheDocumentAndThePicture() {
        when(profileImageRepository.findActive(Fixtures.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INCOMPLETE_VERIFICATION_MATERIAL);
    }

    @Test
    void theProcessCanBeSwitchedOff() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.submit(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.VERIFICATION_DISABLED);
    }

    @Test
    void anUnknownUserCannotSubmit() {
        when(userRepository.findByAwsId("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.submit("ghost"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void theBackSideIsOnlyDownloadedWhenTheDocumentHasOne() throws Exception {
        givenDocumentProfileMatch(95d);
        givenFraud(FraudAssessment.of(5d, List.of(), "clean"));
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.document(user, "documents/passport.png", null)));

        service.submit(Fixtures.USER_ID);

        verify(fileContentFetcher).fetch("user-service:document", "documents/passport.png");
        verify(fileContentFetcher, never()).fetch("user-service:document", "documents/2026/10/01/back.png");
    }

    // ------------------------------------------------------------------------------------------
    // Second phase: the proof of life
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A lived proof whose face owns the document verifies the user")
    void theProofOfLifeVerifiesTheUser() throws Exception {
        givenReferenceAgainstDocument(92d);
        VerificationAttempt attempt = attempt(1, VerificationBand.VERIFIED, 91d);

        VerificationReport report = service.finalizeWithLiveness(user, attempt, succeeded(95d));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.VERIFIED);
        assertThat(report.userStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(report.band()).isEqualTo(VerificationBand.VERIFIED);
        assertThat(report.referenceDocumentMatch()).isEqualTo(92d);
        assertThat(report.livenessConfidence()).isEqualTo(95d);
        assertThat(user.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        verify(retentionService).apply(VerificationOutcome.VERIFIED, Fixtures.USER_ID);
        verify(cognitoGroupService).grantVerified(user.getUsername());
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("A person who cannot prove to be alive goes to a human, and the attempt is closed")
    void aFailedProofOfLifeGoesToManualReview() throws Exception {
        VerificationAttempt attempt = attempt(1, VerificationBand.VERIFIED, 91d);

        VerificationReport report = service.finalizeWithLiveness(
                user,
                attempt,
                new LivenessResult(LivenessStatus.FAILED, 12d, null, null, "spoof-detected"));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.MANUAL_REVIEW);
        assertThat(report.userStatus()).isEqualTo(VerificationStatus.MANUAL_REVIEW);
        assertThat(report.flags()).contains("liveness-failed", "no-second-chance");
        assertThat(attempt.getOutcome()).isEqualTo(VerificationOutcome.MANUAL_REVIEW);
        verify(cognitoGroupService, never()).grantVerified(anyString());
        verify(retentionService).apply(VerificationOutcome.MANUAL_REVIEW, Fixtures.USER_ID);
    }

    @Test
    @DisplayName("Somebody else's face in front of the camera goes to a human too")
    void aLivePersonWithSomebodyElsesDocumentGoesToManualReview() throws Exception {
        givenReferenceAgainstDocument(31d);
        VerificationAttempt attempt = attempt(1, VerificationBand.VERIFIED, 91d);

        VerificationReport report = service.finalizeWithLiveness(user, attempt, succeeded(97d));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.MANUAL_REVIEW);
        assertThat(report.flags()).contains("reference-does-not-match-document");
    }

    @Test
    @DisplayName("A reference that could not be compared is not a pass")
    void aReferenceThatCouldNotBeComparedIsNotAPass() throws Exception {
        when(faceComparator.compareWithStoredObject(any(), any(), any()))
                .thenReturn(FaceMatch.notEvaluated("rekognition-error"));
        VerificationAttempt attempt = attempt(1, VerificationBand.VERIFIED, 91d);

        VerificationReport report = service.finalizeWithLiveness(user, attempt, succeeded(97d));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.MANUAL_REVIEW);
        assertThat(report.flags()).contains("reference-not-evaluated:rekognition-error");
    }

    @Test
    @DisplayName("A proof of life with no reference picture cannot confirm an identity")
    void aProofOfLifeWithoutAReferenceIsNotAPass() throws Exception {
        VerificationAttempt attempt = attempt(1, VerificationBand.VERIFIED, 91d);

        VerificationReport report = service.finalizeWithLiveness(
                user,
                attempt,
                new LivenessResult(LivenessStatus.SUCCEEDED, 97d, null, null, null));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.MANUAL_REVIEW);
        verify(faceComparator, never()).compareWithStoredObject(any(), any(), any());
    }

    @Test
    @DisplayName("A low confidence proof of life is not enough to verify somebody")
    void aLowConfidenceProofOfLifeGoesToAManualReview() throws Exception {
        givenReferenceAgainstDocument(95d);
        VerificationAttempt attempt = attempt(1, VerificationBand.VERIFIED, 91d);

        VerificationReport report = service.finalizeWithLiveness(user, attempt, succeeded(72d));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("Without the document there is nothing to compare the live face with")
    void theLiveFaceIsOnlyComparedWhileTheDocumentIsStillThere() throws Exception {
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());
        VerificationAttempt attempt = attempt(1, VerificationBand.VERIFIED, 91d);

        VerificationReport report = service.finalizeWithLiveness(user, attempt, succeeded(97d));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.MANUAL_REVIEW);
        assertThat(report.flags()).contains("reference-not-evaluated:missing-document");
    }

    @Test
    void theReferenceReplacesTheLivenessKeyOfTheAttempt() throws Exception {
        givenReferenceAgainstDocument(92d);
        VerificationAttempt attempt = attempt(1, VerificationBand.VERIFIED, 91d);

        service.finalizeWithLiveness(user, attempt, succeeded(95d));

        assertThat(attempt.getLivenessKey()).isEqualTo("liveness/session-1/reference.jpg");
        assertThat(attempt.getDecidedAt()).isNotNull();
    }

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    @Test
    void theStateDescribesWhatTheClientStillHasToDo() throws Exception {
        VerificationService.VerificationState state = service.state(Fixtures.USER_ID);

        assertThat(state.status()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(state.attemptsUsed()).isZero();
        assertThat(state.attemptsRemaining()).isEqualTo(3);
        assertThat(state.canAttempt()).isTrue();
        assertThat(state.documentUploaded()).isTrue();
        assertThat(state.livenessCompleted()).isFalse();
        assertThat(state.profileImageUploaded()).isTrue();
        assertThat(state.livenessRequired()).isFalse();
        assertThat(state.lastAttempt()).isNull();
    }

    @Test
    void theStateReportsTheLastAttempt() throws Exception {
        when(attemptRepository.findByUserAwsIdAndTypeOrderByAttemptNumberAsc(
                Fixtures.USER_ID, VerificationAttemptType.FULL))
                .thenReturn(List.of(attempt(1, VerificationBand.MANUAL, 46.5)));

        VerificationService.VerificationState state = service.state(Fixtures.USER_ID);

        assertThat(state.attemptsUsed()).isEqualTo(1);
        assertThat(state.lastAttempt()).isNotNull();
    }

    @Test
    @DisplayName("The state tells the client that the proof of life is the next step")
    void theStateAsksForTheProofOfLife() throws Exception {
        when(attemptRepository.findFirstByUserAwsIdAndTypeAndOutcomeOrderByCreatedAtDesc(
                Fixtures.USER_ID, VerificationAttemptType.FULL, VerificationOutcome.AWAITING_LIVENESS))
                .thenReturn(Optional.of(attempt(1, VerificationBand.VERIFIED, 91d)));
        when(livenessCheckRepository.findFirstByUserAwsIdAndStatusOrderByCreatedAtDesc(
                eq(Fixtures.USER_ID), any())).thenReturn(Optional.empty());

        VerificationService.VerificationState state = service.state(Fixtures.USER_ID);

        assertThat(state.livenessRequired()).isTrue();
        assertThat(state.canAttempt()).isFalse();
    }

    @Test
    void theHistoryIsReturnedNewestFirst() throws Exception {
        when(attemptRepository.findByUserAwsIdAndTypeOrderByAttemptNumberDesc(
                Fixtures.USER_ID, VerificationAttemptType.FULL))
                .thenReturn(List.of(attempt(2, VerificationBand.MANUAL, 50d)));

        assertThat(service.history(Fixtures.USER_ID)).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private void givenDocumentProfileMatch(double similarity) {
        when(faceComparator.compare(any(), any())).thenReturn(FaceMatch.of(similarity));
    }

    private void givenReferenceAgainstDocument(double similarity) {
        when(faceComparator.compareWithStoredObject(any(), any(), any())).thenReturn(FaceMatch.of(similarity));
    }

    private LivenessResult succeeded(double confidence) {
        return new LivenessResult(
                LivenessStatus.SUCCEEDED,
                confidence,
                "fatum-liveness",
                "liveness/session-1/reference.jpg",
                null);
    }

    private void givenFraud(FraudAssessment assessment) {
        when(fraudAnalyzer.assess(any(), any(), anyDouble())).thenReturn(assessment);
    }

    private ExtractedDocument extractedDocument() {
        return new ExtractedDocument(true, "1020", "ID", "JANE DOE", "1998-05-10",
                List.of("AnalyzeID"), List.of(), "AnalyzeID");
    }

    private VerificationAttempt attempt(int number, VerificationBand band, double score) {
        return attempt(number, band, score, VerificationOutcome.PENDING);
    }

    private VerificationAttempt attempt(
            int number,
            VerificationBand band,
            double score,
            VerificationOutcome outcome) {
        return new VerificationAttempt(
                user,
                VerificationAttemptType.FULL,
                number,
                band,
                outcome,
                VerificationDecision.SYSTEM,
                score,
                40d,
                50d,
                0d,
                50d,
                "summary",
                "",
                null,
                null,
                null,
                null);
    }
}
