package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.LivenessFile;
import fatum.model.User;
import fatum.repository.LivenessFileRepository;
import fatum.repository.UserRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StoredFile;
import fatum.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LivenessServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final LivenessFileRepository livenessFileRepository = mock(LivenessFileRepository.class);
    private final FileStorageClient fileStorageClient = mock(FileStorageClient.class);

    private final User user = Fixtures.user();

    private LivenessService service;

    @BeforeEach
    void setUp() {
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(livenessFileRepository.save(any(LivenessFile.class))).thenAnswer(call -> call.getArgument(0));
        when(fileStorageClient.upload(any(), any())).thenReturn(
                new StoredFile("id-1", "liveness/new/frame.png", "liveness-bucket", "frame.png", "image/png", 4L));
        when(fileStorageClient.presignedUrl(any(), any())).thenReturn("https://s3/frame.png");
        service = new LivenessService(
                userRepository,
                livenessFileRepository,
                fileStorageClient,
                new FileStorageProperties());
    }

    @Test
    void storesTheFirstLivenessFrame() throws Exception {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        StoredFileResponse response = service.upload(Fixtures.USER_ID, Fixtures.image("image", "frame.png"));

        assertThat(response.downloadUrl()).isEqualTo("https://s3/frame.png");
        verify(livenessFileRepository).save(any(LivenessFile.class));
    }

    @Test
    void aNewFrameReplacesThePreviousOneAndItsObject() throws Exception {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.liveness(user, "liveness/old/frame.png")));

        service.upload(Fixtures.USER_ID, Fixtures.image("image", "frame.png"));

        verify(fileStorageClient).delete("user-service:liveness", "liveness/old/frame.png");
    }

    @Test
    void aNonImageIsRejected() {
        MockMultipartFile pdf = new MockMultipartFile("image", "frame.pdf", "application/pdf", new byte[]{1});

        assertThatThrownBy(() -> service.upload(Fixtures.USER_ID, pdf))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_LIVENESS_TYPE);
    }

    @Test
    void anEmptyFrameIsRejected() {
        MockMultipartFile empty = new MockMultipartFile("image", "frame.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.upload(Fixtures.USER_ID, empty))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INVALID_LIVENESS);
    }

    @Test
    void anUnknownUserCannotUploadEvidence() {
        when(userRepository.findByAwsId("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.upload("ghost", Fixtures.image("image", "frame.png")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void returnsTheStoredEvidence() throws Exception {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.liveness(user)));

        assertThat(service.get(Fixtures.USER_ID).originalFilename()).isEqualTo("frame.png");
    }

    @Test
    void reportsWhenThereIsNoEvidence() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.FILE_NOT_FOUND);
    }

    @Test
    void anAdministratorCanPointTheReferenceAtAnExistingObject() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        LivenessFile adopted = service.adoptAsReference(
                user,
                new StoredFile("id-2", "profile-images/admin/photo.png", "bucket", "photo.png", "image/png", 10L));

        assertThat(adopted.getLivenessKey()).isEqualTo("profile-images/admin/photo.png");
    }

    @Test
    void adoptingReplacesAnExistingReference() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.liveness(user, "liveness/old/frame.png")));

        LivenessFile adopted = service.adoptAsReference(
                user,
                new StoredFile("id-2", "profile-images/admin/photo.png", "bucket", "photo.png", "image/png", 10L));

        assertThat(adopted.getLivenessKey()).isEqualTo("profile-images/admin/photo.png");
    }

    @Test
    void theStoredObjectIsRemovedWhenTheDatabaseFails() {
        when(livenessFileRepository.save(any(LivenessFile.class)))
                .thenThrow(new IllegalStateException("constraint violation"));

        assertThatThrownBy(() -> service.upload(Fixtures.USER_ID, Fixtures.image("image", "frame.png")))
                .isInstanceOf(IllegalStateException.class);
        verify(fileStorageClient).delete("user-service:liveness", "liveness/new/frame.png");
    }
}
