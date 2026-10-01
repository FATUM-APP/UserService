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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * The trusted picture of a user, called the liveness reference.
 *
 * <p>It is the image every later comparison uses: the identity document is compared with it while
 * verifying, and a new profile picture is compared with it before it is accepted. It cannot be
 * uploaded by hand: only Rekognition can produce it, while running a proof of life, or an
 * administrator while reviewing a case.</p>
 */
@Service
public class LivenessService {

    private static final Logger log = LoggerFactory.getLogger(LivenessService.class);

    private final UserRepository userRepository;
    private final LivenessFileRepository livenessFileRepository;
    private final FileStorageClient fileStorageClient;
    private final FileStorageProperties storageProperties;

    public LivenessService(
            UserRepository userRepository,
            LivenessFileRepository livenessFileRepository,
            FileStorageClient fileStorageClient,
            FileStorageProperties storageProperties) {
        this.userRepository = userRepository;
        this.livenessFileRepository = livenessFileRepository;
        this.fileStorageClient = fileStorageClient;
        this.storageProperties = storageProperties;
    }

    public Optional<LivenessFile> reference(String awsId) {
        return livenessFileRepository.findByUserAwsId(awsId);
    }

    public StoredFileResponse get(String awsId) throws FatumUserException {
        getActiveUser(awsId);
        LivenessFile liveness = livenessFileRepository.findByUserAwsId(awsId)
                .orElseThrow(() -> new FatumUserException(FatumUserException.FILE_NOT_FOUND));
        return toResponse(liveness);
    }

    /**
     * Points the reference at the picture Rekognition produced while running the proof of life.
     *
     * <p>The object is written by Rekognition straight into the liveness bucket, so the bucket is kept
     * with the key: the face comparator reads it from S3 instead of downloading it.</p>
     */
    @Transactional
    public LivenessFile adoptRekognitionReference(User user, String bucket, String objectKey) {
        LivenessFile current = livenessFileRepository.findByUserAwsId(user.getAwsId()).orElse(null);
        LivenessFile reference = current;
        if (reference == null) {
            reference = new LivenessFile(
                    objectKey,
                    fileNameOf(objectKey),
                    "image/jpeg",
                    0L,
                    ReferenceSource.REKOGNITION,
                    bucket,
                    user);
        } else {
            reference.replace(
                    objectKey,
                    fileNameOf(objectKey),
                    "image/jpeg",
                    0L,
                    ReferenceSource.REKOGNITION,
                    bucket);
        }
        return livenessFileRepository.save(reference);
    }

    /**
     * Points the reference at an object that is already stored.
     *
     * <p>Used when an administrator verifies an identity manually: the picture they upload becomes both
     * the profile picture and the reference, so the account keeps a single trusted image.</p>
     */
    @Transactional
    public LivenessFile adoptAsReference(User user, StoredFile storedFile) {
        LivenessFile current = livenessFileRepository.findByUserAwsId(user.getAwsId()).orElse(null);
        LivenessFile reference = current;
        if (reference == null) {
            reference = new LivenessFile(
                    storedFile.key(),
                    storedFile.originalFilename(),
                    storedFile.contentType(),
                    storedFile.size(),
                    ReferenceSource.ADMIN,
                    null,
                    user);
        } else {
            reference.replace(
                    storedFile.key(),
                    storedFile.originalFilename(),
                    storedFile.contentType(),
                    storedFile.size(),
                    ReferenceSource.ADMIN,
                    null);
        }
        return livenessFileRepository.save(reference);
    }

    private StoredFileResponse toResponse(LivenessFile liveness) {
        return new StoredFileResponse(
                liveness.getId(),
                liveness.getOriginalFilename(),
                liveness.getContentType(),
                liveness.getFileSize(),
                presignedQuietly(liveness.getLivenessKey()),
                liveness.getCreatedAt(),
                liveness.getUpdatedAt());
    }

    private String presignedQuietly(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        try {
            return fileStorageClient.presignedUrl(storageProperties.getLivenessRoute(), objectKey);
        } catch (StorageException exception) {
            log.warn("The download URL of {} could not be created", objectKey, exception);
            return null;
        }
    }

    private String fileNameOf(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return "reference.jpg";
        }
        int lastSlash = objectKey.lastIndexOf('/');
        return lastSlash < 0 ? objectKey : objectKey.substring(lastSlash + 1);
    }

    private User getActiveUser(String awsId) throws FatumUserException {
        if (awsId == null || awsId.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = userRepository.findByAwsId(awsId.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }
}
