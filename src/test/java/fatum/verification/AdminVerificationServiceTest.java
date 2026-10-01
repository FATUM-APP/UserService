package fatum.verification;

import fatum.dto.AdminReviewRequest;
import fatum.dto.PendingVerificationResponse;
import fatum.exception.FatumUserException;
import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.StorageEventReason;
import fatum.model.constant.StoredFileType;
import fatum.model.constant.VerificationDecision;
import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;
import fatum.repository.DocumentFileRepository;
import fatum.repository.LivenessFileRepository;
import fatum.repository.ProfileImageRepository;
import fatum.repository.UserRepository;
import fatum.repository.VerificationAttemptRepository;
import fatum.service.CognitoGroupService;
import fatum.service.LivenessService;
import fatum.service.ProfileImageService;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StoredFile;
import fatum.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminVerificationServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final VerificationAttemptRepository attemptRepository = mock(VerificationAttemptRepository.class);
    private final DocumentFileRepository documentFileRepository = mock(DocumentFileRepository.class);
    private final LivenessFileRepository livenessFileRepository = mock(LivenessFileRepository.class);
    private final ProfileImageRepository profileImageRepository = mock(ProfileImageRepository.class);
    private final FileStorageClient fileStorageClient = mock(FileStorageClient.class);
    private final ProfileImageService profileImageService = mock(ProfileImageService.class);
    private final LivenessService livenessService = mock(LivenessService.class);
    private final VerificationRetentionService retentionService = mock(VerificationRetentionService.class);
    private final CognitoGroupService cognitoGroupService = mock(CognitoGroupService.class);

    private User user;

    private AdminVerificationService service;

    @BeforeEach
    void setUp() {
        user = Fixtures.user();
        user.markVerificationStatus(VerificationStatus.MANUAL_REVIEW);
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(documentFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.document(user)));
        when(fileStorageClient.upload(any(), any())).thenReturn(
                new StoredFile("id-9", "profile-images/admin/photo.png", "bucket", "photo.png", "image/png", 4L));
        service = new AdminVerificationService(
                userRepository,
                attemptRepository,
                documentFileRepository,
                livenessFileRepository,
                profileImageRepository,
                fileStorageClient,
                new FileStorageProperties(),
                profileImageService,
                livenessService,
                retentionService,
                cognitoGroupService);
    }

    @Test
    void confirmingTheIdentityAdoptsTheUploadedPictureAsProfilePictureAndLiveness() throws Exception {
        when(attemptRepository.findByUserAwsIdOrderByAttemptNumberAsc(Fixtures.USER_ID))
                .thenReturn(List.of(manualAttempt(1)));

        VerificationReport report = service.review(
                "admin-1",
                new AdminReviewRequest(Fixtures.USER_ID, true, "document checked by hand"),
                Fixtures.image("photo", "photo.png"));

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.VERIFIED);
        assertThat(report.userStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(report.attemptNumber()).isEqualTo(2);
        verify(profileImageService).adoptAsProfilePicture(user, new StoredFile("id-9", "profile-images/admin/photo.png", "bucket", "photo.png", "image/png", 4L));
        verify(livenessService).adoptAsReference(user, new StoredFile("id-9", "profile-images/admin/photo.png", "bucket", "photo.png", "image/png", 4L));
        verify(cognitoGroupService).grantVerified(user.getUsername());
        assertThat(user.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void theAdministratorPictureIsRecordedInTheAuditTrail() throws Exception {
        service.review("admin-1", new AdminReviewRequest(Fixtures.USER_ID, true, null), Fixtures.image("photo", "photo.png"));

        verify(retentionService).recordKept(Fixtures.USER_ID, StoredFileType.PROFILE_IMAGE,
                "profile-images/admin/photo.png", StorageEventReason.ADMIN_VERIFIED,
                "Picture adopted as profile image and liveness reference");
        verify(retentionService).recordKept(Fixtures.USER_ID, StoredFileType.LIVENESS,
                "profile-images/admin/photo.png", StorageEventReason.ADMIN_VERIFIED,
                "Picture adopted as profile image and liveness reference");
    }

    @Test
    void confirmingWithoutAPictureIsRejected() {
        assertThatThrownBy(() -> service.review(
                "admin-1",
                new AdminReviewRequest(Fixtures.USER_ID, true, null),
                null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.ADMIN_REVIEW_PHOTO_REQUIRED);
        verify(fileStorageClient, never()).upload(any(), any());
    }

    @Test
    void rejectingTheIdentityClosesTheCase() throws Exception {
        VerificationReport report = service.review(
                "admin-1",
                new AdminReviewRequest(Fixtures.USER_ID, false, "the picture does not match"),
                null);

        assertThat(report.outcome()).isEqualTo(VerificationOutcome.REJECTED);
        assertThat(report.userStatus()).isEqualTo(VerificationStatus.REJECTED);
        verify(profileImageService, never()).adoptAsProfilePicture(any(), any());
        verify(cognitoGroupService, never()).grantVerified(anyString());
        assertThat(user.getVerificationStatus()).isEqualTo(VerificationStatus.REJECTED);
    }

    @Test
    void theDecisionKeepsWhoDecidedAndWhy() throws Exception {
        when(attemptRepository.findByUserAwsIdOrderByAttemptNumberAsc(Fixtures.USER_ID))
                .thenReturn(List.of(manualAttempt(1)));

        service.review("admin-7", new AdminReviewRequest(Fixtures.USER_ID, false, "expired document"), null);

        ArgumentCaptor<VerificationAttempt> saved = ArgumentCaptor.forClass(VerificationAttempt.class);
        verify(attemptRepository).save(saved.capture());
        assertThat(saved.getValue().getDecision()).isEqualTo(VerificationDecision.ADMIN);
        assertThat(saved.getValue().getDecidedBy()).isEqualTo("admin-7");
        assertThat(saved.getValue().getNotes()).isEqualTo("expired document");
        assertThat(saved.getValue().getAttemptNumber()).isEqualTo(2);
    }

    @Test
    void anUnknownUserCannotBeReviewed() {
        when(userRepository.findByAwsId("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.review("admin-1", new AdminReviewRequest("ghost", false, null), null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void aReviewWithoutUserIsRejected() {
        assertThatThrownBy(() -> service.review("admin-1", new AdminReviewRequest("  ", false, null), null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NULL_VALUE);
        assertThatThrownBy(() -> service.review("admin-1", null, null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NULL_VALUE);
    }

    @Test
    void listsTheCasesWaitingForAnAdministrator() {
        when(userRepository.findByVerificationStatusIn(any())).thenReturn(List.of(user));
        when(attemptRepository.findByUserAwsIdOrderByAttemptNumberAsc(Fixtures.USER_ID))
                .thenReturn(List.of(manualAttempt(1), manualAttempt(2)));

        List<PendingVerificationResponse> pending = service.pending();

        assertThat(pending).singleElement().satisfies(item -> {
            assertThat(item.userAwsId()).isEqualTo(Fixtures.USER_ID);
            assertThat(item.status()).isEqualTo(VerificationStatus.MANUAL_REVIEW);
            assertThat(item.attemptsUsed()).isEqualTo(2);
            assertThat(item.lastOutcome()).isEqualTo(VerificationOutcome.PENDING);
        });
    }

    @Test
    void aUserWithoutAttemptsIsListedWithoutAScore() {
        when(userRepository.findByVerificationStatusIn(any())).thenReturn(List.of(user));
        when(attemptRepository.findByUserAwsIdOrderByAttemptNumberAsc(Fixtures.USER_ID)).thenReturn(List.of());

        assertThat(service.pending()).singleElement()
                .satisfies(item -> assertThat(item.lastScore()).isNull());
    }

    private VerificationAttempt manualAttempt(int number) {
        return new VerificationAttempt(
                user, number, fatum.model.constant.VerificationBand.MANUAL, VerificationOutcome.PENDING,
                VerificationDecision.SYSTEM, 45d, 40d, 50d, 50d, 50d, "summary", "", null, null, null, null);
    }
}
