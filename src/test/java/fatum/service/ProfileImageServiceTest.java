package fatum.service;

import fatum.dto.ProfileImageResponse;
import fatum.exception.FatumUserException;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.ProfileImageStatus;
import fatum.model.constant.VerificationAttemptType;
import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;
import fatum.repository.LivenessFileRepository;
import fatum.repository.ProfileImageRepository;
import fatum.repository.UserRepository;
import fatum.repository.VerificationAttemptRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StorageException;
import fatum.storage.StoredFile;
import fatum.support.Fixtures;
import fatum.verification.ProfileImageChangedEvent;
import fatum.verification.VerificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The profile picture is a security surface once the identity is verified, so the tests describe both
 * worlds: the account nobody has verified yet, and the account whose picture can only be replaced by a
 * photograph of the same person.
 */
class ProfileImageServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ProfileImageRepository profileImageRepository = mock(ProfileImageRepository.class);
    private final LivenessFileRepository livenessFileRepository = mock(LivenessFileRepository.class);
    private final VerificationAttemptRepository attemptRepository = mock(VerificationAttemptRepository.class);
    private final FileStorageClient fileStorageClient = mock(FileStorageClient.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final VerificationProperties verificationProperties = new VerificationProperties();

    private final User user = Fixtures.user();

    private ProfileImageService service;

    @BeforeEach
    void setUp() {
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(profileImageRepository.save(any(ProfileImage.class))).thenAnswer(call -> call.getArgument(0));
        when(attemptRepository.save(any(VerificationAttempt.class))).thenAnswer(call -> call.getArgument(0));
        when(fileStorageClient.upload(any(), any())).thenReturn(
                new StoredFile("id-1", "profile-images/new/avatar.png", "profile-bucket", "avatar.png", "image/png", 4L));
        when(fileStorageClient.presignedUrl(any(), any())).thenReturn("https://s3/avatar.png");
        service = new ProfileImageService(
                userRepository,
                profileImageRepository,
                livenessFileRepository,
                attemptRepository,
                fileStorageClient,
                new FileStorageProperties(),
                verificationProperties,
                eventPublisher);
    }

    // ------------------------------------------------------------------------------------------
    // Account without a verified identity
    // ------------------------------------------------------------------------------------------

    @Test
    void beforeAnyVerificationThePictureCanBeChangedFreely() throws Exception {
        ProfileImageResponse response = service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png"));

        assertThat(response.pendingVerification()).isFalse();
        assertThat(response.image().downloadUrl()).isEqualTo("https://s3/avatar.png");
        verify(profileImageRepository).save(any(ProfileImage.class));
        verify(attemptRepository, never()).save(any(VerificationAttempt.class));
        verify(eventPublisher, never()).publishEvent(any(ProfileImageChangedEvent.class));
    }

    @Test
    void replacingAnExistingPictureDeletesThePreviousObject() throws Exception {
        when(profileImageRepository.findActive(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, "profile-images/old/avatar.png")));

        service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png"));

        verify(fileStorageClient).delete("user-service:profile-image", "profile-images/old/avatar.png");
    }

    // ------------------------------------------------------------------------------------------
    // Verified account: the picture is queued and compared with the live reference
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A verified account never publishes a picture before the comparison has run")
    void aVerifiedAccountQueuesThePicture() throws Exception {
        verified();
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(profileImageRepository.findActive(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, "profile-images/old/avatar.png")));

        ProfileImageResponse response = service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png"));

        assertThat(response.pendingVerification()).isTrue();
        assertThat(response.pendingImage().originalFilename()).isEqualTo("avatar.png");
        assertThat(response.image().downloadUrl()).isEqualTo("https://s3/avatar.png");
        verify(eventPublisher).publishEvent(any(ProfileImageChangedEvent.class));
    }

    @Test
    @DisplayName("The queued picture is written as PENDING and consumes no identity attempt")
    void theQueuedPictureIsWrittenAsPending() throws Exception {
        verified();
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));

        service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png"));

        ArgumentCaptor<ProfileImage> image = ArgumentCaptor.forClass(ProfileImage.class);
        verify(profileImageRepository).save(image.capture());
        assertThat(image.getValue().getStatus()).isEqualTo(ProfileImageStatus.PENDING);

        ArgumentCaptor<VerificationAttempt> attempt = ArgumentCaptor.forClass(VerificationAttempt.class);
        verify(attemptRepository).save(attempt.capture());
        assertThat(attempt.getValue().getType()).isEqualTo(VerificationAttemptType.FACE_ONLY);
    }

    @Test
    @DisplayName("A verified account with no live reference cannot change its picture")
    void aVerifiedAccountWithoutAReferenceIsRefused() {
        verified();
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PROFILE_REFERENCE_MISSING);
        verify(fileStorageClient, never()).upload(any(), any());
    }

    @Test
    void tooManyChangesInADayAreRefused() {
        verified();
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(attemptRepository.countByUserAwsIdAndTypeAndCreatedAtAfter(
                eq(Fixtures.USER_ID), eq(VerificationAttemptType.FACE_ONLY), any()))
                .thenReturn(5L);

        assertThatThrownBy(() -> service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PROFILE_PHOTO_TOO_MANY_CHANGES);
    }

    @Test
    @DisplayName("Queueing a second picture throws away the first one, which was never published")
    void replacingAPendingPictureDeletesThePreviousPendingObject() throws Exception {
        verified();
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(profileImageRepository.findPending(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.pendingProfileImage(user, "profile-images/queued/old.png")));

        service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png"));

        verify(fileStorageClient).delete("user-service:profile-image", "profile-images/queued/old.png");
    }

    // ------------------------------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------------------------------

    @Test
    void aNonImageIsRejected() {
        MockMultipartFile pdf = new MockMultipartFile("image", "cv.pdf", "application/pdf", new byte[]{1});

        assertThatThrownBy(() -> service.replace(Fixtures.USER_ID, pdf))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_IMAGE_TYPE);
    }

    @Test
    void anEmptyImageIsRejected() {
        MockMultipartFile empty = new MockMultipartFile("image", "avatar.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.replace(Fixtures.USER_ID, empty))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_IMAGE);
    }

    @Test
    void anImageWithoutFilenameIsRejected() {
        MockMultipartFile nameless = new MockMultipartFile("image", null, "image/png", new byte[]{1});

        assertThatThrownBy(() -> service.replace(Fixtures.USER_ID, nameless))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_IMAGE);
    }

    @Test
    void anUnknownUserCannotChangeThePicture() {
        when(userRepository.findByAwsId("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.replace("ghost", Fixtures.image("image", "avatar.png")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    // ------------------------------------------------------------------------------------------
    // Reading the picture
    // ------------------------------------------------------------------------------------------

    @Test
    void returnsTheStoredPicture() throws Exception {
        when(profileImageRepository.findActive(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user)));

        ProfileImageResponse response = service.get(Fixtures.USER_ID);

        assertThat(response.image().originalFilename()).isEqualTo("avatar.png");
        assertThat(response.pendingVerification()).isFalse();
    }

    @Test
    @DisplayName("While a picture is being checked both of them are reported, and the pending one is not served")
    void reportsThePictureThatIsBeingChecked() throws Exception {
        when(profileImageRepository.findActive(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, "profile-images/old/avatar.png")));
        when(profileImageRepository.findPending(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.pendingProfileImage(user, "profile-images/new/avatar.png")));

        ProfileImageResponse response = service.get(Fixtures.USER_ID);

        assertThat(response.pendingVerification()).isTrue();
        assertThat(response.pendingImage()).isNotNull();
        assertThat(response.image().originalFilename()).isEqualTo("avatar.png");
    }

    @Test
    @DisplayName("The newest picture change tells the client what happened with the previous one")
    void reportsTheOutcomeOfTheLastChange() throws Exception {
        VerificationAttempt attempt = mock(VerificationAttempt.class);
        when(attempt.getOutcome()).thenReturn(VerificationOutcome.REJECTED);
        when(attemptRepository.findByUserAwsIdAndTypeOrderByAttemptNumberDesc(
                Fixtures.USER_ID, VerificationAttemptType.FACE_ONLY))
                .thenReturn(List.of(attempt, mock(VerificationAttempt.class)));

        assertThat(service.get(Fixtures.USER_ID).lastChangeOutcome()).isEqualTo(VerificationOutcome.REJECTED);
    }

    @Test
    void anAccountWithoutAPictureIsReportedEmpty() throws Exception {
        when(profileImageRepository.findActive(Fixtures.USER_ID)).thenReturn(Optional.empty());
        when(profileImageRepository.findPending(Fixtures.USER_ID)).thenReturn(Optional.empty());

        ProfileImageResponse response = service.get(Fixtures.USER_ID);

        assertThat(response.image()).isNull();
        assertThat(response.pendingVerification()).isFalse();
    }

    @Test
    void findReturnsNullWhenThereIsNoPicture() {
        when(profileImageRepository.findActive(Fixtures.USER_ID)).thenReturn(Optional.empty());

        assertThat(service.find(Fixtures.USER_ID)).isNull();
    }

    @Test
    void aFailingDownloadUrlDoesNotBreakTheResponse() throws Exception {
        when(profileImageRepository.findActive(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user)));
        when(fileStorageClient.presignedUrl(any(), any())).thenThrow(StorageException.unavailable("down", null));

        assertThat(service.get(Fixtures.USER_ID).image().downloadUrl()).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Administrator path
    // ------------------------------------------------------------------------------------------

    @Test
    void anAdministratorCanAdoptAPictureWithoutComparison() {
        when(profileImageRepository.findActive(Fixtures.USER_ID)).thenReturn(Optional.empty());

        ProfileImage image = service.adoptAsProfilePicture(
                user,
                new StoredFile("id-2", "profile-images/admin/photo.png", "bucket", "photo.png", "image/png", 10L));

        assertThat(image.getImageKey()).isEqualTo("profile-images/admin/photo.png");
        assertThat(image.getStatus()).isEqualTo(ProfileImageStatus.ACTIVE);
    }

    @Test
    @DisplayName("Adopting a picture by hand also throws away whatever the user had queued")
    void adoptingAPictureDiscardsThePendingOne() {
        when(profileImageRepository.findPending(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.pendingProfileImage(user, "profile-images/queued/new.png")));
        when(profileImageRepository.findActive(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, "profile-images/old/avatar.png")));

        service.adoptAsProfilePicture(
                user,
                new StoredFile("id-2", "profile-images/admin/photo.png", "bucket", "photo.png", "image/png", 10L));

        verify(fileStorageClient).delete("user-service:profile-image", "profile-images/queued/new.png");
        verify(fileStorageClient).delete("user-service:profile-image", "profile-images/old/avatar.png");
    }

    @Test
    void theStoredObjectIsRemovedWhenTheDatabaseFails() {
        when(profileImageRepository.save(any(ProfileImage.class)))
                .thenThrow(new IllegalStateException("constraint violation"));

        assertThatThrownBy(() -> service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png")))
                .isInstanceOf(IllegalStateException.class);
        verify(fileStorageClient).delete("user-service:profile-image", "profile-images/new/avatar.png");
    }

    private void verified() {
        user.markVerificationStatus(VerificationStatus.VERIFIED);
    }
}