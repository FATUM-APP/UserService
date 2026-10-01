package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.repository.LivenessFileRepository;
import fatum.repository.ProfileImageRepository;
import fatum.repository.UserRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StorageException;
import fatum.storage.StoredFile;
import fatum.support.Fixtures;
import fatum.verification.FileContentFetcher;
import fatum.verification.VerificationProperties;
import fatum.verification.analyzer.FaceComparator;
import fatum.verification.analyzer.FaceMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileImageServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ProfileImageRepository profileImageRepository = mock(ProfileImageRepository.class);
    private final LivenessFileRepository livenessFileRepository = mock(LivenessFileRepository.class);
    private final FileStorageClient fileStorageClient = mock(FileStorageClient.class);
    private final FileContentFetcher fileContentFetcher = mock(FileContentFetcher.class);
    private final FaceComparator faceComparator = mock(FaceComparator.class);

    private final User user = Fixtures.user();
    private final VerificationProperties verificationProperties = new VerificationProperties();

    private ProfileImageService service;

    @BeforeEach
    void setUp() {
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(profileImageRepository.save(any(ProfileImage.class))).thenAnswer(call -> call.getArgument(0));
        when(fileStorageClient.upload(any(), any())).thenReturn(
                new StoredFile("id-1", "profile-images/new/avatar.png", "profile-bucket", "avatar.png", "image/png", 4L));
        when(fileStorageClient.presignedUrl(any(), any())).thenReturn("https://s3/avatar.png");
        when(fileContentFetcher.fetch(any(), any())).thenReturn(new byte[]{9, 9, 9});
        service = new ProfileImageService(
                userRepository,
                profileImageRepository,
                livenessFileRepository,
                fileStorageClient,
                new FileStorageProperties(),
                fileContentFetcher,
                faceComparator,
                verificationProperties);
    }

    @Test
    void beforeAnyVerificationThePictureCanBeChangedFreely() throws Exception {
        StoredFileResponse response = service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png"));

        assertThat(response.downloadUrl()).isEqualTo("https://s3/avatar.png");
        verify(profileImageRepository).save(any(ProfileImage.class));
        verify(faceComparator, never()).compare(any(), any());
    }

    @Test
    void aPictureThatStillMatchesTheLivenessIsAccepted() throws Exception {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.liveness(user)));
        when(faceComparator.compare(any(), any())).thenReturn(FaceMatch.of(92d));

        service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png"));

        verify(profileImageRepository).save(any(ProfileImage.class));
    }

    @Test
    void aPictureOfAnotherPersonIsRejected() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.liveness(user)));
        when(faceComparator.compare(any(), any())).thenReturn(FaceMatch.of(35d));

        assertThatThrownBy(() -> service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PROFILE_PHOTO_MISMATCH);
        verify(fileStorageClient, never()).upload(any(), any());
    }

    @Test
    void whenTheComparisonCannotBeEvaluatedTheChangeIsAccepted() throws Exception {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.liveness(user)));
        when(faceComparator.compare(any(), any())).thenReturn(FaceMatch.notEvaluated("rekognition-error"));

        service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png"));

        verify(profileImageRepository).save(any(ProfileImage.class));
    }

    @Test
    void replacingAnExistingPictureDeletesThePreviousObject() throws Exception {
        when(profileImageRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, "profile-images/old/avatar.png")));

        service.replace(Fixtures.USER_ID, Fixtures.image("image", "avatar.png"));

        verify(fileStorageClient).delete("user-service:profile-image", "profile-images/old/avatar.png");
    }

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

    @Test
    void returnsTheStoredPicture() throws Exception {
        when(profileImageRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.profileImage(user)));

        StoredFileResponse response = service.get(Fixtures.USER_ID);

        assertThat(response.originalFilename()).isEqualTo("avatar.png");
    }

    @Test
    void reportsWhenThereIsNoPicture() {
        when(profileImageRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.FILE_NOT_FOUND);
    }

    @Test
    void findReturnsNullWhenThereIsNoPicture() {
        when(profileImageRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        assertThat(service.find(Fixtures.USER_ID)).isNull();
    }

    @Test
    void aFailingDownloadUrlDoesNotBreakTheResponse() throws Exception {
        when(profileImageRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.profileImage(user)));
        when(fileStorageClient.presignedUrl(any(), any())).thenThrow(StorageException.unavailable("down", null));

        assertThat(service.get(Fixtures.USER_ID).downloadUrl()).isNull();
    }

    @Test
    void anAdministratorCanAdoptAPictureWithoutComparison() {
        when(profileImageRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        ProfileImage image = service.adoptAsProfilePicture(
                user,
                new StoredFile("id-2", "profile-images/admin/photo.png", "bucket", "photo.png", "image/png", 10L));

        assertThat(image.getImageKey()).isEqualTo("profile-images/admin/photo.png");
        verify(faceComparator, never()).compare(any(), any());
    }

    @Test
    void adoptingAPictureReplacesThePreviousOne() {
        when(profileImageRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, "profile-images/old/avatar.png")));

        service.adoptAsProfilePicture(
                user,
                new StoredFile("id-2", "profile-images/admin/photo.png", "bucket", "photo.png", "image/png", 10L));

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
}
