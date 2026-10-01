package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.LivenessFile;
import fatum.model.User;
import fatum.model.constant.ReferenceSource;
import fatum.repository.LivenessFileRepository;
import fatum.repository.UserRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StorageException;
import fatum.storage.StoredFile;
import fatum.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The trusted picture of an account can only come from Rekognition or from an administrator, so these
 * tests are about where it lives and who produced it, not about uploading it.
 */
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
        when(fileStorageClient.presignedUrl(any(), any())).thenReturn("https://s3/frame.png");
        service = new LivenessService(
                userRepository,
                livenessFileRepository,
                fileStorageClient,
                new FileStorageProperties());
    }

    @Test
    void returnsTheReferenceWithADownloadUrl() throws Exception {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.liveness(user)));

        StoredFileResponse response = service.get(Fixtures.USER_ID);

        assertThat(response.originalFilename()).isEqualTo("frame.png");
        assertThat(response.downloadUrl()).isEqualTo("https://s3/frame.png");
    }

    @Test
    void reportsWhenThereIsNoReferenceYet() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(Fixtures.USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.FILE_NOT_FOUND);
    }

    @Test
    void anUnknownUserHasNoReference() {
        when(userRepository.findByAwsId("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.get("ghost"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("The reference Rekognition produces keeps its bucket, so it never has to be downloaded")
    void adoptingTheRekognitionReferenceKeepsTheBucket() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        LivenessFile reference = service.adoptRekognitionReference(
                user,
                "fatum-liveness",
                "liveness/session-9/reference.jpg");

        assertThat(reference.getLivenessKey()).isEqualTo("liveness/session-9/reference.jpg");
        assertThat(reference.getStorageBucket()).isEqualTo("fatum-liveness");
        assertThat(reference.getSource()).isEqualTo(ReferenceSource.REKOGNITION);
        assertThat(reference.hasStorageBucket()).isTrue();
        assertThat(reference.getOriginalFilename()).isEqualTo("reference.jpg");
    }

    @Test
    void aNewProofOfLifeReplacesThePreviousReference() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.liveness(user, "liveness/old/frame.png")));

        service.adoptRekognitionReference(user, "fatum-liveness", "liveness/session-9/reference.jpg");

        ArgumentCaptor<LivenessFile> saved = ArgumentCaptor.forClass(LivenessFile.class);
        verify(livenessFileRepository).save(saved.capture());
        assertThat(saved.getValue().getLivenessKey()).isEqualTo("liveness/session-9/reference.jpg");
        assertThat(saved.getValue().getSource()).isEqualTo(ReferenceSource.REKOGNITION);
    }

    @Test
    @DisplayName("A picture adopted by an administrator lives in the ordinary liveness route")
    void adoptingAnUploadedPictureUsesTheAdminSource() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());

        LivenessFile reference = service.adoptAsReference(
                user,
                new StoredFile("id-3", "liveness/admin/photo.png", "bucket", "photo.png", "image/png", 12L));

        assertThat(reference.getSource()).isEqualTo(ReferenceSource.ADMIN);
        assertThat(reference.hasStorageBucket()).isFalse();
        assertThat(reference.getFileSize()).isEqualTo(12L);
    }

    @Test
    void aFailingDownloadUrlDoesNotBreakTheResponse() throws Exception {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.liveness(user)));
        when(fileStorageClient.presignedUrl(any(), any())).thenThrow(StorageException.unavailable("down", null));

        assertThat(service.get(Fixtures.USER_ID).downloadUrl()).isNull();
    }
}