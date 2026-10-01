package fatum.verification;

import fatum.exception.FatumUserException;
import fatum.model.DocumentFile;
import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;
import fatum.repository.DocumentFileRepository;
import fatum.repository.LivenessFileRepository;
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
import fatum.verification.analyzer.VerificationScoreCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerificationServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final DocumentFileRepository documentFileRepository = mock(DocumentFileRepository.class);
    private final LivenessFileRepository livenessFileRepository = mock(LivenessFileRepository.class);
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
    private LivenessFile liveness;
    private ProfileImage profileImage;

    private VerificationService service;

    @BeforeEach
    void setUp() {
        user = Fixtures.user();
        document = Fixtures.document(user);
        liveness = Fixtures.liveness(user);
        profileImage = Fixtures.profileImage(user);

        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(document));
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(liveness));
        when(profileImageRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(profileImage));
        when(attemptRepository.findByUserAwsIdOrderByAttemptNumberAsc(Fixtures.USER_ID)).thenReturn(List.of());
        when(fileContentFetcher.fetch(anyString(), anyString()))
                .thenReturn(new byte[]{1}, new byte[]{2}, new byte[]{3});
        when(documentAnalyzer.extract(any(), any(), any())).thenReturn(extractedDocument());
        when(fieldMatcher.match(any(), any())).thenReturn(new DocumentFieldMatcher.MatchResult(true, 100d, List.of(), List.of()));

        service = new VerificationService(
                userRepository,
                documentFileRepository,
                livenessFileRepository,
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

    @Test
    void aHighScoreVerifiesTheUserAndKeepsOnlyTheLivenessAndThePicture() throws Exception {
        givenFaces(95d, 90d);
        givenFraud(FraudAssessment.of(5d, List.of(), "clean"));

        VerificationReport report = service.submit(Fixtures.USER_ID);

        assertThat(report.band()).isEqualTo(VerificationBand.VERIFIED);
        assertThat(report.outcome()).isEqualTo(VerificationOutcome.VERIFIED);
        assertThat(report.userStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(report.score()).isGreaterThan(90d);
        assertThat(report.attemptNumber()).isEqualTo(1);
        assertThat(report.attemptsRemaining()).isEqualTo(2);
        assertThat(user.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        verify(retentionService).apply(VerificationOutcome.VERIFIED, Fixtures.USER_ID);
        verify(cognitoGroupService).grantVerified(user.getUsername());
        verify(userRepository).save(user);
    }

    @Test
    void aManualScoreKeepsTheUserUnverifiedAndWipesTheEvidence() throws Exception {
        givenFaces(50d, 50d);
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
        givenFaces(50d, 50d);
        givenFraud(FraudAssessment.of(50d, List.of(), "unclear"));
        when(fieldMatcher.match(any(), any()))
                .thenReturn(new DocumentFieldMatcher.MatchResult(true, 40d, List.of(), List.of()));
        when(attemptRepository.findByUserAwsIdOrderByAttemptNumberAsc(Fixtures.USER_ID))
                .thenReturn(List.of(attempt(1, VerificationBand.MANUAL, 46.5), attempt(2, VerificationBand.MANUAL, 47.5)));

        VerificationReport report = service.submit(Fixtures.USER_ID);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.MANUAL_REVIEW);
        assertThat(report.userStatus()).isEqualTo(VerificationStatus.MANUAL_REVIEW);
        assertThat(report.attemptNumber()).isEqualTo(3);
        verify(retentionService).apply(VerificationOutcome.MANUAL_REVIEW, Fixtures.USER_ID);
    }

    @Test
    void aLowScoreRejectsTheUserOnTheFirstAttempt() throws Exception {
        givenFaces(15d, 20d);
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
        givenFaces(99d, 99d);
        givenFraud(FraudAssessment.of(90d, List.of("altered-number"), "the number does not exist"));

        VerificationReport report = service.submit(Fixtures.USER_ID);

        assertThat(report.score()).isGreaterThan(70d);
        assertThat(report.band()).isEqualTo(VerificationBand.REJECTED);
        assertThat(report.outcome()).isEqualTo(VerificationOutcome.REJECTED);
        assertThat(report.flags()).contains("fraud:altered-number", "forged-document");
    }

    @Test
    void aDocumentThatDoesNotMatchTheUserIsRejectedEvenWithAPerfectScore() throws Exception {
        givenFaces(5d, 99d);
        givenFraud(FraudAssessment.of(0d, List.of(), "clean"));

        VerificationReport report = service.submit(Fixtures.USER_ID);

        assertThat(report.band()).isEqualTo(VerificationBand.REJECTED);
        assertThat(report.flags()).contains("document-does-not-match-liveness");
    }

    @Test
    void anAlreadyVerifiedUserCannotSubmitAgain() {
        user.markVerificationStatus(VerificationStatus.VERIFIED);

        assertThatThrownBy(() -> service.submit(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.VERIFICATION_ALREADY_COMPLETED);
    }

    @Test
    void aUserWithoutAttemptsLeftCannotSubmit() {
        when(attemptRepository.findByUserAwsIdOrderByAttemptNumberAsc(Fixtures.USER_ID))
                .thenReturn(List.of(attempt(1, VerificationBand.REJECTED, 10d)));

        assertThatThrownBy(() -> service.submit(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NO_ATTEMPTS_LEFT);
    }

    @Test
    void theIdentityCannotBeVerifiedWithoutAllTheEvidence() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

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
    void theAttemptKeepsTheEvidenceAndTheScores() throws Exception {
        givenFaces(95d, 90d);
        givenFraud(FraudAssessment.of(5d, List.of(), "clean"));

        service.submit(Fixtures.USER_ID);

        ArgumentCaptor<VerificationAttempt> saved = ArgumentCaptor.forClass(VerificationAttempt.class);
        verify(attemptRepository).save(saved.capture());
        VerificationAttempt attempt = saved.getValue();
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getDocumentFrontKey()).isEqualTo(document.getFrontKey());
        assertThat(attempt.getLivenessKey()).isEqualTo(liveness.getLivenessKey());
        assertThat(attempt.getProfileImageKey()).isEqualTo(profileImage.getImageKey());
        assertThat(attempt.getSummary()).contains("Attempt 1/3");
        assertThat(attempt.getCreatedAt()).isNotNull();
    }

    @Test
    void theStateDescribesWhatTheClientStillHasToUpload() throws Exception {
        VerificationService.VerificationState state = service.state(Fixtures.USER_ID);

        assertThat(state.status()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(state.attemptsUsed()).isZero();
        assertThat(state.attemptsRemaining()).isEqualTo(3);
        assertThat(state.canAttempt()).isTrue();
        assertThat(state.documentUploaded()).isTrue();
        assertThat(state.livenessUploaded()).isTrue();
        assertThat(state.profileImageUploaded()).isTrue();
        assertThat(state.lastAttempt()).isNull();
    }

    @Test
    void theStateReportsTheLastAttempt() throws Exception {
        when(attemptRepository.findByUserAwsIdOrderByAttemptNumberAsc(Fixtures.USER_ID))
                .thenReturn(List.of(attempt(1, VerificationBand.MANUAL, 46.5)));

        VerificationService.VerificationState state = service.state(Fixtures.USER_ID);

        assertThat(state.attemptsUsed()).isEqualTo(1);
        assertThat(state.lastAttempt()).isNotNull();
    }

    @Test
    void theHistoryIsReturnedNewestFirst() throws Exception {
        when(attemptRepository.findByUserAwsIdOrderByAttemptNumberDesc(Fixtures.USER_ID))
                .thenReturn(List.of(attempt(2, VerificationBand.MANUAL, 50d)));

        assertThat(service.history(Fixtures.USER_ID)).hasSize(1);
    }

    @Test
    void theBackSideIsOnlyDownloadedWhenTheDocumentHasOne() throws Exception {
        givenFaces(95d, 90d);
        givenFraud(FraudAssessment.of(5d, List.of(), "clean"));
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.document(user, "documents/passport.png", null)));

        service.submit(Fixtures.USER_ID);

        verify(fileContentFetcher).fetch("user-service:document", "documents/passport.png");
        verify(fileContentFetcher, never()).fetch("user-service:document", "documents/2026/10/01/back.png");
    }

    private void givenFaces(double documentSimilarity, double profileSimilarity) {
        when(faceComparator.compare(any(), any()))
                .thenReturn(FaceMatch.of(documentSimilarity), FaceMatch.of(profileSimilarity));
    }

    private void givenFraud(FraudAssessment assessment) {
        when(fraudAnalyzer.assess(any(), any(), anyDouble())).thenReturn(assessment);
    }

    private ExtractedDocument extractedDocument() {
        return new ExtractedDocument(true, "1020", "ID", "JANE DOE", "1998-05-10",
                List.of("AnalyzeID"), List.of(), "AnalyzeID");
    }

    private VerificationAttempt attempt(int number, VerificationBand band, double score) {
        return new VerificationAttempt(
                user, number, band, VerificationOutcome.PENDING,
                fatum.model.constant.VerificationDecision.SYSTEM, score,
                40d, 50d, 50d, 50d, "summary", "", null, null, null, null);
    }
}
