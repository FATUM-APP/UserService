package fatum.verification;

import fatum.model.DocumentFile;
import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.StorageEvent;
import fatum.model.User;
import fatum.model.constant.StorageEventAction;
import fatum.model.constant.StorageEventReason;
import fatum.model.constant.StoredFileType;
import fatum.model.constant.VerificationOutcome;
import fatum.repository.DocumentFileRepository;
import fatum.repository.LivenessFileRepository;
import fatum.repository.ProfileImageRepository;
import fatum.repository.StorageEventRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StorageException;
import fatum.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retention policy is the reason a user can say "my document is gone" long after the fact, so every
 * test also asserts what the audit trail recorded.
 */
class VerificationRetentionServiceTest {

    private final DocumentFileRepository documentRepository = mock(DocumentFileRepository.class);
    private final LivenessFileRepository livenessRepository = mock(LivenessFileRepository.class);
    private final ProfileImageRepository profileImageRepository = mock(ProfileImageRepository.class);
    private final StorageEventRepository storageEventRepository = mock(StorageEventRepository.class);
    private final FileStorageClient fileStorageClient = mock(FileStorageClient.class);

    private final User user = Fixtures.user();

    private VerificationRetentionService service;

    @BeforeEach
    void setUp() {
        FileStorageProperties properties = new FileStorageProperties();
        service = new VerificationRetentionService(
                fileStorageClient,
                properties,
                documentRepository,
                livenessRepository,
                profileImageRepository,
                storageEventRepository);
    }

    @Test
    void aManualResultWithRetriesLeftWipesEveryPieceOfEvidence() {
        when(documentRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.document(user)));
        when(livenessRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.liveness(user)));
        when(profileImageRepository.findAllByUserAwsId(Fixtures.USER_ID))
                .thenReturn(List.of(Fixtures.profileImage(user)));

        service.apply(VerificationOutcome.PENDING, Fixtures.USER_ID);

        verify(fileStorageClient).delete("user-service:document", "documents/2026/10/01/front.png");
        verify(fileStorageClient).delete("user-service:document", "documents/2026/10/01/back.png");
        verify(fileStorageClient).delete("user-service:liveness", "liveness/2026/10/01/frame.png");
        verify(fileStorageClient).delete("user-service:profile-image", "profile-images/2026/10/01/avatar.png");
        verify(documentRepository).delete(any(DocumentFile.class));
        verify(livenessRepository).delete(any(LivenessFile.class));
        verify(profileImageRepository).deleteAll(any());
        assertThat(recordedReasons()).containsOnly(StorageEventReason.MANUAL_RETRY_RESET.name());
    }

    @Test
    @DisplayName("While the proof of life is pending nothing is deleted: the paid step is about to need it")
    void aPendingProofOfLifeKeepsEverything() {
        when(documentRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.document(user)));
        when(livenessRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());
        when(profileImageRepository.findActive(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.profileImage(user)));

        service.apply(VerificationOutcome.AWAITING_LIVENESS, Fixtures.USER_ID);

        verify(fileStorageClient, never()).delete(any(), any());
        verify(documentRepository, never()).delete(any());
        verify(profileImageRepository, never()).deleteAll(any());
        List<StorageEvent> events = capturedEvents();
        assertThat(events).hasSize(3);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getAction()).isEqualTo(StorageEventAction.RETAINED);
            assertThat(event.getReason()).isEqualTo(StorageEventReason.LIVENESS_PENDING);
        });
    }

    @Test
    void aVerifiedIdentityOnlyLosesTheDocument() {
        when(documentRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.document(user)));

        service.apply(VerificationOutcome.VERIFIED, Fixtures.USER_ID);

        verify(fileStorageClient).delete("user-service:document", "documents/2026/10/01/front.png");
        verify(fileStorageClient, never()).delete(eq("user-service:liveness"), any());
        verify(fileStorageClient, never()).delete(eq("user-service:profile-image"), any());
        verify(livenessRepository, never()).delete(any());
        verify(profileImageRepository, never()).deleteAll(any());

        List<StorageEvent> events = capturedEvents();
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getReason()).isEqualTo(StorageEventReason.VERIFIED);
            assertThat(event.getAction()).isEqualTo(StorageEventAction.DELETED);
        });
        assertThat(events).extracting(StorageEvent::getFileType)
                .containsExactlyInAnyOrder(StoredFileType.DOCUMENT_FRONT, StoredFileType.DOCUMENT_BACK);
    }

    @Test
    @DisplayName("A case for an administrator keeps the document and the picture Rekognition produced")
    void aCaseForAnAdministratorKeepsTheEvidence() {
        when(livenessRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(profileImageRepository.findAllByUserAwsId(Fixtures.USER_ID))
                .thenReturn(List.of(Fixtures.profileImage(user)));

        service.apply(VerificationOutcome.MANUAL_REVIEW, Fixtures.USER_ID);

        verify(documentRepository, never()).delete(any());
        verify(fileStorageClient, never()).delete(eq("user-service:liveness"), any());
        verify(profileImageRepository).deleteAll(any());
        assertThat(recordedReasons()).containsOnly(StorageEventReason.ADMIN_REVIEW_REQUIRED.name());
        assertThat(capturedEvents()).extracting(StorageEvent::getFileType)
                .contains(StoredFileType.LIVENESS);
    }

    @Test
    @DisplayName("A reference uploaded by hand is not evidence, so it goes away with the pictures")
    void aHandUploadedReferenceIsDeletedOnReview() {
        when(livenessRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.liveness(user)));

        service.apply(VerificationOutcome.MANUAL_REVIEW, Fixtures.USER_ID);

        verify(fileStorageClient).delete("user-service:liveness", "liveness/2026/10/01/frame.png");
        verify(livenessRepository).delete(any(LivenessFile.class));
    }

    @Test
    void aRejectedIdentityIsTreatedLikeAManualReview() {
        when(livenessRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.liveness(user)));

        service.apply(VerificationOutcome.REJECTED, Fixtures.USER_ID);

        verify(livenessRepository).delete(any(LivenessFile.class));
        verify(documentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("A verified account can hold two pictures and both of them go away")
    void everyPictureOfTheUserIsDeleted() {
        when(profileImageRepository.findAllByUserAwsId(Fixtures.USER_ID)).thenReturn(List.of(
                Fixtures.profileImage(user, "profile-images/old/avatar.png"),
                Fixtures.pendingProfileImage(user, "profile-images/new/avatar.png")));

        service.apply(VerificationOutcome.MANUAL_REVIEW, Fixtures.USER_ID);

        verify(fileStorageClient).delete("user-service:profile-image", "profile-images/old/avatar.png");
        verify(fileStorageClient).delete("user-service:profile-image", "profile-images/new/avatar.png");
    }

    @Test
    void aDocumentWithoutBackSideOnlyDeletesOneObject() {
        when(documentRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.document(user, "documents/passport.png", null)));

        service.apply(VerificationOutcome.VERIFIED, Fixtures.USER_ID);

        verify(fileStorageClient).delete("user-service:document", "documents/passport.png");
        assertThat(capturedEvents()).hasSize(1);
    }

    @Test
    void nothingHappensWhenThereIsNoEvidence() {
        service.apply(VerificationOutcome.PENDING, Fixtures.USER_ID);

        verify(fileStorageClient, never()).delete(any(), any());
        verify(storageEventRepository, never()).save(any());
    }

    @Test
    void aStorageFailureDoesNotStopTheDeletionOfTheRowAndIsRecorded() {
        when(documentRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.document(user)));
        doThrow(StorageException.unavailable("bucket on fire", null))
                .when(fileStorageClient).delete(any(), any());

        service.apply(VerificationOutcome.VERIFIED, Fixtures.USER_ID);

        verify(documentRepository).delete(any(DocumentFile.class));
        assertThat(capturedEvents()).hasSize(2);
        assertThat(capturedEvents()).allSatisfy(event ->
                assertThat(event.getDetail()).contains("bucket on fire"));
    }

    @Test
    void theObjectIsKeptWhenTheProfilePictureStillUsesIt() {
        User user = Fixtures.user();
        String sharedKey = "profile-images/2026/10/01/adopted.png";
        when(livenessRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.liveness(user, sharedKey)));
        when(profileImageRepository.findAllByUserAwsId(Fixtures.USER_ID))
                .thenReturn(List.of(Fixtures.profileImage(user, sharedKey)));

        service.keepOnlyEvidence(Fixtures.USER_ID);

        // The liveness row disappears but the object survives: the profile picture still points at it.
        verify(fileStorageClient, never()).delete(any(), any());
        assertThat(capturedEvents()).extracting(StorageEvent::getAction)
                .contains(StorageEventAction.RETAINED);
    }

    @Test
    void recordsThatAnObjectWasKeptOnPurpose() {
        service.recordKept(Fixtures.USER_ID, StoredFileType.PROFILE_IMAGE, "profile-images/adopted.png",
                StorageEventReason.ADMIN_VERIFIED, "Adopted by the administrator");

        List<StorageEvent> events = capturedEvents();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getAction()).isEqualTo(StorageEventAction.RETAINED);
            assertThat(event.getReason()).isEqualTo(StorageEventReason.ADMIN_VERIFIED);
            assertThat(event.getObjectKey()).isEqualTo("profile-images/adopted.png");
        });
    }

    @Test
    void wipesEverythingOnRequest() {
        when(documentRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(Fixtures.document(user)));

        service.wipeEverythingForRetry(Fixtures.USER_ID);

        verify(documentRepository).delete(any(DocumentFile.class));
    }

    private List<String> recordedReasons() {
        return capturedEvents().stream().map(event -> event.getReason().name()).toList();
    }

    private List<StorageEvent> capturedEvents() {
        ArgumentCaptor<StorageEvent> captor = ArgumentCaptor.forClass(StorageEvent.class);
        verify(storageEventRepository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }
}