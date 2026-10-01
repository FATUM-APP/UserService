package fatum.verification;

import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationAttemptType;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationOutcome;
import fatum.repository.LivenessFileRepository;
import fatum.repository.ProfileImageRepository;
import fatum.repository.VerificationAttemptRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StorageException;
import fatum.verification.analyzer.FaceComparator;
import fatum.verification.analyzer.FaceMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

/**
 * Decides whether a new profile picture of a verified account can be published.
 *
 * <p>The rule is binary and there is no human in the middle: either the new picture still shows the
 * person of the live reference and it becomes the visible one, or it is discarded and the previous
 * picture stays. A picture of somebody else on a verified account is not a review case, it is a
 * change that never happened.</p>
 *
 * <p>When the comparison cannot be evaluated the picture is discarded as well. Failing open would let
 * anybody who has taken over a session replace the face of a verified account with anything while
 * Rekognition is down; the user can simply upload the picture again.</p>
 *
 * <p>It runs after the upload has committed and in another thread, so the client is not kept waiting
 * for Rekognition. The listener is the natural seam for moving the work to a Lambda: the event already
 * carries everything a worker needs, and the only thing a worker would do differently is post its
 * verdict back instead of writing it here.</p>
 */
@Component
public class PendingProfilePhotoVerifier {

    private static final Logger log = LoggerFactory.getLogger(PendingProfilePhotoVerifier.class);

    private final ProfileImageRepository profileImageRepository;
    private final LivenessFileRepository livenessFileRepository;
    private final VerificationAttemptRepository attemptRepository;
    private final FaceComparator faceComparator;
    private final FileContentFetcher fileContentFetcher;
    private final FileStorageClient fileStorageClient;
    private final FileStorageProperties storageProperties;
    private final VerificationProperties properties;

    public PendingProfilePhotoVerifier(
            ProfileImageRepository profileImageRepository,
            LivenessFileRepository livenessFileRepository,
            VerificationAttemptRepository attemptRepository,
            FaceComparator faceComparator,
            FileContentFetcher fileContentFetcher,
            FileStorageClient fileStorageClient,
            FileStorageProperties storageProperties,
            VerificationProperties properties) {
        this.profileImageRepository = profileImageRepository;
        this.livenessFileRepository = livenessFileRepository;
        this.attemptRepository = attemptRepository;
        this.faceComparator = faceComparator;
        this.fileContentFetcher = fileContentFetcher;
        this.fileStorageClient = fileStorageClient;
        this.storageProperties = storageProperties;
        this.properties = properties;
    }

    @Async("verificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void onProfileImageChanged(ProfileImageChangedEvent event) {
        verify(event.userAwsId(), event.profileImageId());
    }

    /** Compares the pending picture with the live reference and publishes or discards it. */
    @Transactional
    public void verify(String userAwsId, String profileImageId) {
        ProfileImage pending = profileImageRepository.findById(profileImageId).orElse(null);
        if (pending == null || !pending.isPending()) {
            return;
        }
        LivenessFile reference = livenessFileRepository.findByUserAwsId(userAwsId).orElse(null);
        if (reference == null) {
            discard(userAwsId, pending, FaceMatch.notEvaluated("missing-reference"));
            return;
        }
        FaceMatch match = compareWithReference(pending, reference);
        if (match.evaluated() && match.similarity() >= properties.getProfilePhotoChangeThreshold()) {
            publish(userAwsId, pending, match);
        } else {
            discard(userAwsId, pending, match);
        }
    }

    private FaceMatch compareWithReference(ProfileImage pending, LivenessFile reference) {
        try {
            byte[] candidate = fileContentFetcher.fetch(
                    storageProperties.getProfileImageRoute(),
                    pending.getImageKey());
            if (reference.hasStorageBucket()) {
                return faceComparator.compareWithStoredObject(
                        candidate,
                        reference.getStorageBucket(),
                        reference.getLivenessKey());
            }
            byte[] trusted = fileContentFetcher.fetch(
                    storageProperties.getLivenessRoute(),
                    reference.getLivenessKey());
            return faceComparator.compare(trusted, candidate);
        } catch (StorageException exception) {
            log.error("The faces of {} could not be compared", pending.getUser().getAwsId(), exception);
            return FaceMatch.notEvaluated("storage-error");
        }
    }

    /** The new picture becomes the visible one and the previous object is deleted. */
    private void publish(String userAwsId, ProfileImage pending, FaceMatch match) {
        ProfileImage active = profileImageRepository.findActive(userAwsId).orElse(null);
        String previousKey = active == null ? null : active.getImageKey();
        ProfileImage published = active;
        if (published == null) {
            pending.promote();
            published = pending;
        } else {
            published.replace(
                    pending.getImageKey(),
                    pending.getOriginalFilename(),
                    pending.getContentType(),
                    pending.getFileSize());
        }
        profileImageRepository.save(published);
        if (published != pending) {
            profileImageRepository.delete(pending);
        }
        if (previousKey != null && !previousKey.equals(published.getImageKey())) {
            deleteQuietly(previousKey);
        }
        resolveAttempt(userAwsId, VerificationBand.VERIFIED, VerificationOutcome.VERIFIED, match,
                "The new profile picture matches the live reference (similarity %.2f)".formatted(match.similarity()));
        log.info("The profile picture of {} was replaced with a picture that matches the live reference", userAwsId);
    }

    /** The new picture is thrown away and the visible one is left untouched. */
    private void discard(String userAwsId, ProfileImage pending, FaceMatch match) {
        String key = pending.getImageKey();
        profileImageRepository.delete(pending);
        deleteQuietly(key);
        String reason = match.evaluated()
                ? "The new profile picture does not match the live reference (similarity %.2f)".formatted(match.similarity())
                : "The new profile picture could not be compared with the live reference: " + match.detail();
        resolveAttempt(userAwsId, VerificationBand.REJECTED, VerificationOutcome.REJECTED, match, reason);
        log.info("The new profile picture of {} was discarded: {}", userAwsId, reason);
    }

    private void resolveAttempt(
            String userAwsId,
            VerificationBand band,
            VerificationOutcome outcome,
            FaceMatch match,
            String summary) {
        VerificationAttempt attempt = attemptRepository
                .findFirstByUserAwsIdAndTypeAndOutcomeOrderByCreatedAtDesc(
                        userAwsId,
                        VerificationAttemptType.FACE_ONLY,
                        VerificationOutcome.PENDING)
                .orElse(null);
        if (attempt == null) {
            log.warn("The picture change of {} has no open attempt to close", userAwsId);
            return;
        }
        String flags = "profile-photo-change," + (match.evaluated() ? "compared" : "not-evaluated");
        attempt.resolve(
                band,
                outcome,
                null,
                match.evaluated() ? match.similarity() : 0d,
                summary,
                flags,
                null);
        attemptRepository.save(attempt);
    }

    private void deleteQuietly(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            fileStorageClient.delete(storageProperties.getProfileImageRoute(), objectKey);
        } catch (StorageException exception) {
            log.warn("The object {} could not be deleted from the profile image route", objectKey, exception);
        }
    }
}