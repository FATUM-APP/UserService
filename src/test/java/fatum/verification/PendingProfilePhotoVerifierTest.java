package fatum.verification;

import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.ProfileImageStatus;
import fatum.model.constant.VerificationAttemptType;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationDecision;
import fatum.model.constant.VerificationOutcome;
import fatum.repository.LivenessFileRepository;
import fatum.repository.ProfileImageRepository;
import fatum.repository.VerificationAttemptRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StorageException;
import fatum.support.Fixtures;
import fatum.verification.analyzer.FaceComparator;
import fatum.verification.analyzer.FaceMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Changing the picture of a verified account is decided without a human: it is either published or
 * discarded. The tests pin that down, including the cases where Rekognition cannot answer at all,
 * because failing open there would let anybody replace the face of a verified account.
 */
class PendingProfilePhotoVerifierTest {

    private static final String PENDING_KEY = "profile-images/new/avatar.png";

    private final ProfileImageRepository profileImageRepository = mock(ProfileImageRepository.class);
    private final LivenessFileRepository livenessFileRepository = mock(LivenessFileRepository.class);
    private final VerificationAttemptRepository attemptRepository = mock(VerificationAttemptRepository.class);
    private final FaceComparator faceComparator = mock(FaceComparator.class);
    private final FileContentFetcher fileContentFetcher = mock(FileContentFetcher.class);
    private final FileStorageClient fileStorageClient = mock(FileStorageClient.class);

    private final VerificationProperties properties = new VerificationProperties();
    private final User user = Fixtures.user();

    private PendingProfilePhotoVerifier verifier;
    private ProfileImage pending;
    private VerificationAttempt attempt;

    @BeforeEach
    void setUp() {
        pending = Fixtures.pendingProfileImage(user, PENDING_KEY);
        attempt = new VerificationAttempt(
                user,
                VerificationAttemptType.FACE_ONLY,
                1,
                VerificationBand.MANUAL,
                VerificationOutcome.PENDING,
                VerificationDecision.SYSTEM,
                0d,
                0d,
                0d,
                0d,
                0d,
                "queued",
                "profile-photo-change",
                null,
                null,
                null,
                PENDING_KEY);

        when(profileImageRepository.findById(anyString())).thenReturn(Optional.of(pending));
        when(profileImageRepository.save(any(ProfileImage.class))).thenAnswer(call -> call.getArgument(0));
        when(attemptRepository.findFirstByUserAwsIdAndTypeAndOutcomeOrderByCreatedAtDesc(
                Fixtures.USER_ID, VerificationAttemptType.FACE_ONLY, VerificationOutcome.PENDING))
                .thenReturn(Optional.of(attempt));
        when(fileContentFetcher.fetch(anyString(), anyString())).thenReturn(new byte[]{7, 7, 7});

        verifier = new PendingProfilePhotoVerifier(
                profileImageRepository,
                livenessFileRepository,
                attemptRepository,
                faceComparator,
                fileContentFetcher,
                fileStorageClient,
                new FileStorageProperties(),
                properties);
    }

    // ------------------------------------------------------------------------------------------
    // Accepted
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The picture of the same person replaces the visible one and the old object is deleted")
    void theSamePersonIsPublished() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(profileImageRepository.findActive(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, "profile-images/old/avatar.png")));
        when(faceComparator.compareWithStoredObject(any(), any(), any())).thenReturn(FaceMatch.of(93d));

        verifier.verify(Fixtures.USER_ID, "image-1");

        ArgumentCaptor<ProfileImage> saved = ArgumentCaptor.forClass(ProfileImage.class);
        verify(profileImageRepository).save(saved.capture());
        assertThat(saved.getValue().getImageKey()).isEqualTo(PENDING_KEY);
        assertThat(saved.getValue().getStatus()).isEqualTo(ProfileImageStatus.ACTIVE);
        verify(profileImageRepository).delete(pending);
        verify(fileStorageClient).delete("user-service:profile-image", "profile-images/old/avatar.png");
        assertThat(attempt.getOutcome()).isEqualTo(VerificationOutcome.VERIFIED);
        assertThat(attempt.getReferenceDocumentMatch()).isEqualTo(93d);
    }

    @Test
    @DisplayName("An account whose picture was never published simply promotes the new one")
    void theFirstPictureIsPromoted() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(profileImageRepository.findActive(Fixtures.USER_ID)).thenReturn(Optional.empty());
        when(faceComparator.compareWithStoredObject(any(), any(), any())).thenReturn(FaceMatch.of(88d));

        verifier.verify(Fixtures.USER_ID, "image-1");

        assertThat(pending.getStatus()).isEqualTo(ProfileImageStatus.ACTIVE);
        verify(profileImageRepository, never()).delete(any(ProfileImage.class));
        verify(fileStorageClient, never()).delete(any(), anyString());
    }

    @Test
    @DisplayName("A reference uploaded by hand is downloaded and compared locally")
    void aHandUploadedReferenceIsDownloaded() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.liveness(user)));
        when(profileImageRepository.findActive(Fixtures.USER_ID)).thenReturn(Optional.empty());
        when(faceComparator.compare(any(), any())).thenReturn(FaceMatch.of(90d));

        verifier.verify(Fixtures.USER_ID, "image-1");

        verify(fileContentFetcher).fetch("user-service:liveness", "liveness/2026/10/01/frame.png");
        verify(faceComparator).compare(any(), any());
        assertThat(attempt.getOutcome()).isEqualTo(VerificationOutcome.VERIFIED);
    }

    // ------------------------------------------------------------------------------------------
    // Discarded
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A picture of somebody else never reaches the account and is deleted from the bucket")
    void anotherPersonIsDiscarded() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(profileImageRepository.findActive(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, "profile-images/old/avatar.png")));
        when(faceComparator.compareWithStoredObject(any(), any(), any())).thenReturn(FaceMatch.of(31d));

        verifier.verify(Fixtures.USER_ID, "image-1");

        verify(profileImageRepository, never()).save(any(ProfileImage.class));
        verify(profileImageRepository).delete(pending);
        verify(fileStorageClient).delete("user-service:profile-image", PENDING_KEY);
        assertThat(attempt.getOutcome()).isEqualTo(VerificationOutcome.REJECTED);
        assertThat(attempt.getFlags()).contains("profile-photo-change");
    }

    @Test
    @DisplayName("A comparison that could not be evaluated throws the picture away instead of publishing it")
    void aFailedComparisonFailsClosed() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(faceComparator.compareWithStoredObject(any(), any(), any()))
                .thenReturn(FaceMatch.notEvaluated("rekognition-error"));

        verifier.verify(Fixtures.USER_ID, "image-1");

        verify(profileImageRepository).delete(pending);
        assertThat(attempt.getOutcome()).isEqualTo(VerificationOutcome.REJECTED);
        assertThat(attempt.getFlags()).contains("not-evaluated");
    }

    @Test
    void aStorageFailureFailsClosedToo() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(fileContentFetcher.fetch(anyString(), anyString()))
                .thenThrow(StorageException.unavailable("storage down", null));

        verifier.verify(Fixtures.USER_ID, "image-1");

        verify(profileImageRepository).delete(pending);
        assertThat(attempt.getOutcome()).isEqualTo(VerificationOutcome.REJECTED);
    }

    @Test
    @DisplayName("A verified account without a reference cannot publish anything")
    void aMissingReferenceDiscardsThePicture() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.empty());
        when(profileImageRepository.findActive(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.profileImage(user, "profile-images/old/avatar.png")));

        verifier.verify(Fixtures.USER_ID, "image-1");

        verify(profileImageRepository).delete(pending);
        verify(fileStorageClient).delete("user-service:profile-image", PENDING_KEY);
        assertThat(attempt.getOutcome()).isEqualTo(VerificationOutcome.REJECTED);
    }

    // ------------------------------------------------------------------------------------------
    // Idempotence
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A picture that was already decided is not compared twice")
    void anAlreadyDecidedPictureIsIgnored() {
        when(profileImageRepository.findById(anyString()))
                .thenReturn(Optional.of(Fixtures.profileImage(user, PENDING_KEY)));

        verifier.verify(Fixtures.USER_ID, "image-1");

        verify(faceComparator, never()).compare(any(), any());
        verify(profileImageRepository, never()).delete(any(ProfileImage.class));
    }

    @Test
    void aPictureThatNoLongerExistsIsIgnored() {
        when(profileImageRepository.findById(anyString())).thenReturn(Optional.empty());

        verifier.verify(Fixtures.USER_ID, "image-1");

        verify(faceComparator, never()).compare(any(), any());
    }

    @Test
    @DisplayName("The comparison threshold comes from the configuration")
    void theThresholdComesFromTheConfiguration() {
        properties.setProfilePhotoChangeThreshold(95);
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(faceComparator.compareWithStoredObject(any(), any(), any())).thenReturn(FaceMatch.of(90d));

        verifier.verify(Fixtures.USER_ID, "image-1");

        verify(profileImageRepository).delete(pending);
    }

    @Test
    @DisplayName("The event is handled after the upload transaction has committed")
    void theEventIsHandledAfterCommit() {
        LivenessFile reference = Fixtures.rekognitionReference(user);
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID)).thenReturn(Optional.of(reference));
        when(faceComparator.compareWithStoredObject(any(), any(), any())).thenReturn(FaceMatch.of(93d));

        verifier.onProfileImageChanged(new ProfileImageChangedEvent(Fixtures.USER_ID, "image-1"));

        assertThat(attempt.getOutcome()).isEqualTo(VerificationOutcome.VERIFIED);
        verify(attemptRepository).save(attempt);
    }

    @Test
    void everyPictureChangeIsRecordedInTheAttemptHistory() {
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(faceComparator.compareWithStoredObject(any(), any(), any())).thenReturn(FaceMatch.of(93d));

        verifier.verify(Fixtures.USER_ID, "image-1");

        assertThat(attempt.getSummary()).contains("profile picture");
        assertThat(attempt.getType()).isEqualTo(VerificationAttemptType.FACE_ONLY);
    }

    @Test
    void theAttemptIsLeftOpenWhenThereIsNothingToClose() {
        when(attemptRepository.findFirstByUserAwsIdAndTypeAndOutcomeOrderByCreatedAtDesc(
                eq(Fixtures.USER_ID), any(), any())).thenReturn(Optional.empty());
        when(livenessFileRepository.findByUserAwsId(Fixtures.USER_ID))
                .thenReturn(Optional.of(Fixtures.rekognitionReference(user)));
        when(faceComparator.compareWithStoredObject(any(), any(), any())).thenReturn(FaceMatch.of(93d));

        verifier.verify(Fixtures.USER_ID, "image-1");

        verify(attemptRepository, never()).save(any());
    }
}
