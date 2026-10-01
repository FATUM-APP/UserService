package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.repository.LivenessFileRepository;
import fatum.repository.ProfileImageRepository;
import fatum.repository.UserRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StorageException;
import fatum.storage.StoredFile;
import fatum.verification.FileContentFetcher;
import fatum.verification.VerificationProperties;
import fatum.verification.analyzer.FaceComparator;
import fatum.verification.analyzer.FaceMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Profile picture of a user.
 *
 * <p>Once the identity has been verified, the profile picture becomes a security surface: it is the
 * picture other users see, so it must keep showing the same person. A change is therefore accepted only
 * when the new picture still matches the liveness reference, which is the only evidence kept after a
 * successful verification. Before any verification exists there is nothing to compare with, so the
 * change is free.</p>
 */
@Service
public class ProfileImageService {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageService.class);

    private final UserRepository userRepository;
    private final ProfileImageRepository profileImageRepository;
    private final LivenessFileRepository livenessFileRepository;
    private final FileStorageClient fileStorageClient;
    private final FileStorageProperties storageProperties;
    private final FileContentFetcher fileContentFetcher;
    private final FaceComparator faceComparator;
    private final VerificationProperties verificationProperties;

    public ProfileImageService(
            UserRepository userRepository,
            ProfileImageRepository profileImageRepository,
            LivenessFileRepository livenessFileRepository,
            FileStorageClient fileStorageClient,
            FileStorageProperties storageProperties,
            FileContentFetcher fileContentFetcher,
            FaceComparator faceComparator,
            VerificationProperties verificationProperties) {
        this.userRepository = userRepository;
        this.profileImageRepository = profileImageRepository;
        this.livenessFileRepository = livenessFileRepository;
        this.fileStorageClient = fileStorageClient;
        this.storageProperties = storageProperties;
        this.fileContentFetcher = fileContentFetcher;
        this.faceComparator = faceComparator;
        this.verificationProperties = verificationProperties;
    }

    @Transactional
    public StoredFileResponse replace(String awsId, MultipartFile file) throws FatumUserException {
        User user = getActiveUser(awsId);
        validateImage(file);
        ProfileImage current = profileImageRepository.findByUserAwsId(awsId).orElse(null);
        String previousKey = current == null ? null : current.getImageKey();

        requireSamePersonAsLiveness(awsId, file);

        StoredFile uploaded = fileStorageClient.upload(file, storageProperties.getProfileImageRoute());
        try {
            ProfileImage image = current;
            if (image == null) {
                image = new ProfileImage(
                        uploaded.key(),
                        uploaded.originalFilename(),
                        uploaded.contentType(),
                        uploaded.size(),
                        user);
            } else {
                image.replace(
                        uploaded.key(),
                        uploaded.originalFilename(),
                        uploaded.contentType(),
                        uploaded.size());
            }
            ProfileImage saved = profileImageRepository.save(image);
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
        ProfileImage image = profileImageRepository.findByUserAwsId(awsId)
                .orElseThrow(() -> new FatumUserException(FatumUserException.FILE_NOT_FOUND));
        return toResponse(image);
    }

    /**
     * Sets an already stored object as the profile picture, without the liveness comparison.
     *
     * <p>Only used when an administrator verifies an identity manually and uploads the trusted picture
     * themselves.</p>
     */
    @Transactional
    public ProfileImage adoptAsProfilePicture(User user, StoredFile storedFile) {
        ProfileImage current = profileImageRepository.findByUserAwsId(user.getAwsId()).orElse(null);
        String previousKey = current == null ? null : current.getImageKey();
        ProfileImage image = current;
        if (image == null) {
            image = new ProfileImage(
                    storedFile.key(),
                    storedFile.originalFilename(),
                    storedFile.contentType(),
                    storedFile.size(),
                    user);
        } else {
            image.replace(
                    storedFile.key(),
                    storedFile.originalFilename(),
                    storedFile.contentType(),
                    storedFile.size());
        }
        ProfileImage saved = profileImageRepository.save(image);
        if (previousKey != null && !previousKey.equals(storedFile.key())) {
            deleteQuietly(previousKey);
        }
        return saved;
    }

    public StoredFileResponse find(String awsId) {
        return profileImageRepository.findByUserAwsId(awsId)
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * Accepts the change only when the new picture still shows the person of the liveness reference.
     *
     * <p>When the comparison cannot be evaluated (Rekognition disabled or unavailable) the change is
     * allowed and a warning is logged: blocking every profile update during an AWS incident would be
     * worse than accepting a picture that a later verification can review.</p>
     */
    private void requireSamePersonAsLiveness(String awsId, MultipartFile file) throws FatumUserException {
        LivenessFile liveness = livenessFileRepository.findByUserAwsId(awsId).orElse(null);
        if (liveness == null) {
            return;
        }
        byte[] candidate = read(file);
        byte[] reference = fileContentFetcher.fetch(
                storageProperties.getLivenessRoute(),
                liveness.getLivenessKey());
        FaceMatch match = faceComparator.compare(reference, candidate);
        if (!match.evaluated()) {
            log.warn("The new profile picture of {} could not be compared with the liveness reference: {}",
                    awsId, match.detail());
            return;
        }
        if (match.similarity() < verificationProperties.getProfilePhotoChangeThreshold()) {
            throw new FatumUserException(FatumUserException.PROFILE_PHOTO_MISMATCH);
        }
    }

    private StoredFileResponse toResponse(ProfileImage image) {
        return new StoredFileResponse(
                image.getId(),
                image.getOriginalFilename(),
                image.getContentType(),
                image.getFileSize(),
                presignedQuietly(image.getImageKey()),
                image.getCreatedAt(),
                image.getUpdatedAt());
    }

    private String presignedQuietly(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        try {
            return fileStorageClient.presignedUrl(storageProperties.getProfileImageRoute(), objectKey);
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
            fileStorageClient.delete(storageProperties.getProfileImageRoute(), objectKey);
        } catch (StorageException exception) {
            log.warn("The object {} could not be deleted from the profile image route", objectKey, exception);
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
            throw new FatumUserException(FatumUserException.INVALID_IMAGE);
        }
        if (!StringUtils.hasText(file.getOriginalFilename())) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE);
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE_TYPE);
        }
    }

    private byte[] read(MultipartFile file) throws FatumUserException {
        try {
            return file.getBytes();
        } catch (java.io.IOException exception) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE);
        }
    }
}
