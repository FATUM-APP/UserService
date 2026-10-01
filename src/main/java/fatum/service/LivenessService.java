package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.LivenessFile;
import fatum.model.User;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * Liveness evidence of a user.
 *
 * <p>The frame is the reference picture of the account: it is compared with the picture on the identity
 * document and with the profile picture. It is uploaded before the verification is submitted and can be
 * replaced by uploading a new one.</p>
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

    @Transactional
    public StoredFileResponse upload(String awsId, MultipartFile file) throws FatumUserException {
        User user = getActiveUser(awsId);
        validateImage(file);
        LivenessFile current = livenessFileRepository.findByUserAwsId(awsId).orElse(null);
        String previousKey = current == null ? null : current.getLivenessKey();

        StoredFile uploaded = fileStorageClient.upload(file, storageProperties.getLivenessRoute());
        try {
            LivenessFile saved = adoptAsReference(user, uploaded, current);
            if (previousKey != null && !previousKey.equals(uploaded.key())) {
                deleteQuietly(previousKey);
            }
            return toResponse(saved);
        } catch (RuntimeException exception) {
            deleteQuietly(uploaded.key());
            throw exception;
        }
    }

    public StoredFileResponse get(String awsId) throws FatumUserException {
        getActiveUser(awsId);
        LivenessFile liveness = livenessFileRepository.findByUserAwsId(awsId)
                .orElseThrow(() -> new FatumUserException(FatumUserException.FILE_NOT_FOUND));
        return toResponse(liveness);
    }

    /**
     * Points the liveness reference at an object that is already stored.
     *
     * <p>Used when an administrator verifies an identity manually: the picture they upload becomes both
     * the profile picture and the liveness reference, so the account keeps a single trusted image.</p>
     */
    @Transactional
    public LivenessFile adoptAsReference(User user, StoredFile storedFile) {
        LivenessFile current = livenessFileRepository.findByUserAwsId(user.getAwsId()).orElse(null);
        return adoptAsReference(user, storedFile, current);
    }

    private LivenessFile adoptAsReference(User user, StoredFile storedFile, LivenessFile current) {
        LivenessFile liveness = current;
        if (liveness == null) {
            liveness = new LivenessFile(
                    storedFile.key(),
                    storedFile.originalFilename(),
                    storedFile.contentType(),
                    storedFile.size(),
                    user);
        } else {
            liveness.replace(
                    storedFile.key(),
                    storedFile.originalFilename(),
                    storedFile.contentType(),
                    storedFile.size());
        }
        return livenessFileRepository.save(liveness);
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

    private void deleteQuietly(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            fileStorageClient.delete(storageProperties.getLivenessRoute(), objectKey);
        } catch (StorageException exception) {
            log.warn("The object {} could not be deleted from the liveness route", objectKey, exception);
        }
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

    private void validateImage(MultipartFile file) throws FatumUserException {
        if (file == null || file.isEmpty()) {
            throw new FatumUserException(FatumUserException.INVALID_LIVENESS);
        }
        if (!StringUtils.hasText(file.getOriginalFilename())) {
            throw new FatumUserException(FatumUserException.INVALID_LIVENESS);
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new FatumUserException(FatumUserException.INVALID_LIVENESS_TYPE);
        }
    }
}
