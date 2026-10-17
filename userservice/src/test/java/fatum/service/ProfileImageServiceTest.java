package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.repository.ProfileImageRepository;
import fatum.repository.UserRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StoredFile;
import fatum.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileImageServiceTest {

    private static final String USER_ID = Fixtures.USER_ID;
    private static final String ROUTE = "user-service:profile-image";
    private static final String NEW_KEY = "profile-images/2026/10/03/new.png";
    private static final String OLD_KEY = "profile-images/2026/10/01/old.png";
    private static final StoredFile UPLOADED =
            new StoredFile("obj-1", NEW_KEY, "fatum-profile-images", "avatar.png", "image/png", 4L);

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProfileImageRepository profileImageRepository;

    @Mock
    private FileStorageClient storage;

    private ProfileImageService service;
    private User user;

    @BeforeEach
    void setUp() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setProfileImageRoute(ROUTE);
        service = new ProfileImageService(userRepository, profileImageRepository, storage, properties);
        user = Fixtures.user();
    }

    // ------------------------------------------------------------------ replace

    @Test
    void storesTheFirstPictureOfAnAccount() throws FatumUserException {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(profileImageRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());
        when(storage.upload(any(MultipartFile.class), eq(ROUTE))).thenReturn(UPLOADED);
        when(profileImageRepository.save(any(ProfileImage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.presignedUrl(ROUTE, NEW_KEY)).thenReturn("https://s3/" + NEW_KEY);

        StoredFileResponse response = service.replace(USER_ID, Fixtures.image("avatar.png"));

        assertThat(response.originalFilename()).isEqualTo("avatar.png");
        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(response.size()).isEqualTo(4L);
        assertThat(response.downloadUrl()).isEqualTo("https://s3/" + NEW_KEY);
        verify(storage, never()).delete(anyString(), anyString());
    }

    @Test
    void replacingAPictureStoresTheNewObjectAndRemovesTheOldOne() throws FatumUserException {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(profileImageRepository.findByUserAwsId(USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, OLD_KEY)));
        when(storage.upload(any(MultipartFile.class), eq(ROUTE))).thenReturn(UPLOADED);
        when(profileImageRepository.save(any(ProfileImage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.presignedUrl(ROUTE, NEW_KEY)).thenReturn("https://s3/" + NEW_KEY);

        service.replace(USER_ID, Fixtures.image("avatar.png"));

        verify(storage).delete(ROUTE, OLD_KEY);
    }

    @Test
    void aFailedSaveRemovesTheObjectThatWasJustUploaded() throws FatumUserException {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(profileImageRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());
        when(storage.upload(any(MultipartFile.class), eq(ROUTE))).thenReturn(UPLOADED);
        when(profileImageRepository.save(any(ProfileImage.class)))
                .thenThrow(new IllegalStateException("database is down"));

        assertThatThrownBy(() -> service.replace(USER_ID, Fixtures.image("avatar.png")))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).delete(ROUTE, NEW_KEY);
    }

    @Test
    void aBlankAccountIdIsRejected() {
        assertThatThrownBy(() -> service.replace("  ", Fixtures.image("avatar.png")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NULL_VALUE);
    }

    @Test
    void anUnknownAccountIsRejected() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.replace(USER_ID, Fixtures.image("avatar.png")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void anEmptyPictureIsRejected() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        MockMultipartFile empty =
                new MockMultipartFile("image", "avatar.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.replace(USER_ID, empty))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_IMAGE);
    }

    @Test
    void aPictureWithoutFilenameIsRejected() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);

        assertThatThrownBy(() -> service.replace(USER_ID, Fixtures.image("image", "")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_IMAGE);
    }

    @Test
    void somethingThatIsNotAnImageIsRejected() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);

        assertThatThrownBy(() -> service.replace(USER_ID, Fixtures.file("image", "notes.txt", "text/plain")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_IMAGE_TYPE);
    }

    // ------------------------------------------------------------------ lookups

    @Test
    void findsTheCurrentPicture() throws FatumUserException {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(profileImageRepository.findByUserAwsId(USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, NEW_KEY)));
        when(storage.presignedUrl(ROUTE, NEW_KEY)).thenReturn("https://s3/" + NEW_KEY);

        assertThat(service.get(USER_ID).downloadUrl()).isEqualTo("https://s3/" + NEW_KEY);
    }

    @Test
    void anAccountWithoutPictureIsReported() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(user);
        when(profileImageRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.FILE_NOT_FOUND);
    }

    @Test
    void theOptionalLookupReturnsNullInsteadOfFailing() {
        when(profileImageRepository.findByUserAwsId(USER_ID)).thenReturn(Optional.empty());

        assertThat(service.find(USER_ID)).isNull();
    }

    @Test
    void theOptionalLookupReturnsThePictureWhenThereIsOne() {
        when(profileImageRepository.findByUserAwsId(USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, NEW_KEY)));
        when(storage.presignedUrl(ROUTE, NEW_KEY)).thenReturn("https://s3/" + NEW_KEY);

        assertThat(service.find(USER_ID)).isNotNull();
        assertThat(service.find(USER_ID).downloadUrl()).isEqualTo("https://s3/" + NEW_KEY);
    }
}