package fatum.verification;

import fatum.model.DocumentFile;
import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.StorageEvent;
import fatum.model.constant.ReferenceSource;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Applies the retention policy to the evidence of a verification.
 *
 * <p>The business rule is simple to state: the service keeps only what it still needs. A verified
 * identity no longer needs the document; a case that goes to an administrator needs the document and
 * the live reference, because that is the evidence of what happened; a retry needs nothing at all; and
 * a case that is waiting for the proof of life needs everything, because that is exactly what the
 * proof is about to use.</p>
 *
 * <p>Every action is written to {@code storage_events}, which is what allows answering later why an
 * object is no longer in the bucket. A failed object deletion never aborts the verification: it is
 * logged and recorded, and the row is removed anyway so the user is not left holding evidence the
 * platform promised to delete.</p>
 */
@Service
public class VerificationRetentionService {

    private static final Logger log = LoggerFactory.getLogger(VerificationRetentionService.class);

    private final FileStorageClient fileStorageClient;
    private final FileStorageProperties storageProperties;
    private final DocumentFileRepository documentFileRepository;
    private final LivenessFileRepository livenessFileRepository;
    private final ProfileImageRepository profileImageRepository;
    private final StorageEventRepository storageEventRepository;

    public VerificationRetentionService(
            FileStorageClient fileStorageClient,
            FileStorageProperties storageProperties,
            DocumentFileRepository documentFileRepository,
            LivenessFileRepository livenessFileRepository,
            ProfileImageRepository profileImageRepository,
            StorageEventRepository storageEventRepository) {
        this.fileStorageClient = fileStorageClient;
        this.storageProperties = storageProperties;
        this.documentFileRepository = documentFileRepository;
        this.livenessFileRepository = livenessFileRepository;
        this.profileImageRepository = profileImageRepository;
        this.storageEventRepository = storageEventRepository;
    }

    /** Applies the policy that corresponds to the outcome of the attempt. */
    public void apply(VerificationOutcome outcome, String userAwsId) {
        switch (outcome) {
            case PENDING -> wipeEverythingForRetry(userAwsId);
            case AWAITING_LIVENESS -> keepEverythingForLiveness(userAwsId);
            case VERIFIED -> keepOnlyLivenessAndProfile(userAwsId);
            case REJECTED, MANUAL_REVIEW -> keepOnlyEvidence(userAwsId);
        }
    }

    /** Manual band with retries left: the user starts over, so nothing is kept. */
    public void wipeEverythingForRetry(String userAwsId) {
        deleteDocument(userAwsId, StorageEventReason.MANUAL_RETRY_RESET);
        deleteLiveness(userAwsId, StorageEventReason.MANUAL_RETRY_RESET);
        deleteProfileImages(userAwsId, StorageEventReason.MANUAL_RETRY_RESET);
    }

    /**
     * The proof of life is about to run, so the document and the picture it has to be compared with
     * must stay exactly where they are.
     */
    public void keepEverythingForLiveness(String userAwsId) {
        documentFileRepository.findByUserAwsId(userAwsId).ifPresent(document -> {
            recordKept(userAwsId, StoredFileType.DOCUMENT_FRONT, document.getFrontKey(),
                    StorageEventReason.LIVENESS_PENDING, "The document is needed to check the proof of life");
            if (document.hasBackSide()) {
                recordKept(userAwsId, StoredFileType.DOCUMENT_BACK, document.getBackKey(),
                        StorageEventReason.LIVENESS_PENDING, "The document is needed to check the proof of life");
            }
        });
        profileImageRepository.findActive(userAwsId).ifPresent(image -> recordKept(
                userAwsId,
                StoredFileType.PROFILE_IMAGE,
                image.getImageKey(),
                StorageEventReason.LIVENESS_PENDING,
                "The picture is needed to check the proof of life"));
    }

    /** Identity confirmed: the document goes away, the reference and the profile picture stay. */
    public void keepOnlyLivenessAndProfile(String userAwsId) {
        deleteDocument(userAwsId, StorageEventReason.VERIFIED);
    }

    /**
     * Case escalated: the document is the evidence the administrator needs, and so is the reference
     * Rekognition produced, because it shows who was really in front of the camera.
     */
    public void keepOnlyEvidence(String userAwsId) {
        keepRekognitionReference(userAwsId);
        deleteProfileImages(userAwsId, StorageEventReason.ADMIN_REVIEW_REQUIRED);
    }

    /** Records that a piece of evidence was deliberately kept. */
    public void recordKept(String userAwsId, StoredFileType fileType, String objectKey, StorageEventReason reason, String detail) {
        save(userAwsId, fileType, objectKey, StorageEventAction.RETAINED, reason, detail);
    }

    private void keepRekognitionReference(String userAwsId) {
        Optional<LivenessFile> reference = livenessFileRepository.findByUserAwsId(userAwsId);
        if (reference.isEmpty()) {
            return;
        }
        if (reference.get().getSource() == ReferenceSource.REKOGNITION) {
            recordKept(userAwsId, StoredFileType.LIVENESS, reference.get().getLivenessKey(),
                    StorageEventReason.ADMIN_REVIEW_REQUIRED,
                    "The picture Rekognition produced during the proof of life");
            return;
        }
        deleteLiveness(userAwsId, StorageEventReason.ADMIN_REVIEW_REQUIRED);
    }

    private void deleteDocument(String userAwsId, StorageEventReason reason) {
        Optional<DocumentFile> document = documentFileRepository.findByUserAwsId(userAwsId);
        if (document.isEmpty()) {
            return;
        }
        DocumentFile file = document.get();
        deleteObject(userAwsId, StoredFileType.DOCUMENT_FRONT, storageProperties.getDocumentRoute(), file.getFrontKey(), reason);
        if (file.hasBackSide()) {
            deleteObject(userAwsId, StoredFileType.DOCUMENT_BACK, storageProperties.getDocumentRoute(), file.getBackKey(), reason);
        }
        documentFileRepository.delete(file);
    }

    private void deleteLiveness(String userAwsId, StorageEventReason reason) {
        Optional<LivenessFile> liveness = livenessFileRepository.findByUserAwsId(userAwsId);
        if (liveness.isEmpty()) {
            return;
        }
        LivenessFile file = liveness.get();
        if (isStillReferenced(userAwsId, file.getLivenessKey(), StoredFileType.LIVENESS)) {
            recordKept(userAwsId, StoredFileType.LIVENESS, file.getLivenessKey(), reason,
                    "The object is still used as the profile picture");
        } else {
            deleteObject(userAwsId, StoredFileType.LIVENESS, storageProperties.getLivenessRoute(), file.getLivenessKey(), reason);
        }
        livenessFileRepository.delete(file);
    }

    /** A verified account can hold two rows at once, and both of them have to go. */
    private void deleteProfileImages(String userAwsId, StorageEventReason reason) {
        List<ProfileImage> images = List.copyOf(profileImageRepository.findAllByUserAwsId(userAwsId));
        for (ProfileImage image : images) {
            if (isStillReferenced(userAwsId, image.getImageKey(), StoredFileType.PROFILE_IMAGE)) {
                recordKept(userAwsId, StoredFileType.PROFILE_IMAGE, image.getImageKey(), reason,
                        "The object is still used as the liveness reference");
            } else {
                deleteObject(userAwsId, StoredFileType.PROFILE_IMAGE, storageProperties.getProfileImageRoute(), image.getImageKey(), reason);
            }
        }
        profileImageRepository.deleteAll(images);
    }

    /**
     * An administrator can set the same picture as profile image and as liveness reference; in that
     * case the object must survive the deletion of one of the two rows.
     */
    private boolean isStillReferenced(String userAwsId, String objectKey, StoredFileType deleting) {
        if (!StringUtils.hasText(objectKey)) {
            return false;
        }
        if (deleting != StoredFileType.PROFILE_IMAGE) {
            boolean usedByProfile = profileImageRepository.findAllByUserAwsId(userAwsId).stream()
                    .anyMatch(image -> objectKey.equals(image.getImageKey()));
            if (usedByProfile) {
                return true;
            }
        }
        if (deleting != StoredFileType.LIVENESS) {
            return livenessFileRepository.findByUserAwsId(userAwsId)
                    .map(liveness -> objectKey.equals(liveness.getLivenessKey()))
                    .orElse(false);
        }
        return false;
    }

    private void deleteObject(
            String userAwsId,
            StoredFileType fileType,
            String route,
            String objectKey,
            StorageEventReason reason) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            fileStorageClient.delete(route, objectKey);
            save(userAwsId, fileType, objectKey, StorageEventAction.DELETED, reason, null);
        } catch (StorageException exception) {
            log.error("The object {} of {} could not be deleted", objectKey, userAwsId, exception);
            save(userAwsId, fileType, objectKey, StorageEventAction.DELETED, reason,
                    "The storage service refused the deletion: " + exception.getMessage());
        }
    }

    private void save(
            String userAwsId,
            StoredFileType fileType,
            String objectKey,
            StorageEventAction action,
            StorageEventReason reason,
            String detail) {
        storageEventRepository.save(new StorageEvent(userAwsId, fileType, objectKey, action, reason, detail));
    }
}