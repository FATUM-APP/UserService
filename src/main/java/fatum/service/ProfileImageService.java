package fatum.service;

import fatum.dto.ProfileImageResponse;
import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.LivenessFile;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.VerificationAttempt;
import fatum.model.constant.ProfileImageStatus;
import fatum.model.constant.VerificationAttemptType;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationDecision;
import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;
import fatum.repository.LivenessFileRepository;
import fatum.repository.ProfileImageRepository;
import fatum.repository.UserRepository;
import fatum.repository.VerificationAttemptRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StorageException;
import fatum.storage.StoredFile;
import fatum.verification.ProfileImageChangedEvent;
import fatum.verification.VerificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Profile picture of a user.
 *
 * <p>Before an identity is verified there is nothing to compare a picture with, so a change is applied
 * straight away. Once the identity is verified the picture becomes a security surface: it is the face
 * other users see, and it must keep being the same person as the live reference. A change then never
 * goes to a human, it is simply accepted or refused by the face comparator, and it is refused until
 * the comparison says otherwise.</p>
 */
@Service
public class ProfileImageService {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageService.class);

    private final UserRepository userRepository;
    private final ProfileImageRepository profileImageRepository;
    private final LivenessFileRepository livenessFileRepository;
    private final VerificationAttemptRepository attemptRepository;
    private final FileStorageClient fileStorageClient;
    private final FileStorageProperties storageProperties;
    private final VerificationProperties verificationProperties;
    private final ApplicationEventPublisher eventPublisher;

    public ProfileImageService(
            UserRepository userRepository,
            ProfileImageRepository profileImageRepository,
            LivenessFileRepository livenessFileRepository,
            VerificationAttemptRepository attemptRepository,
            FileStorageClient fileStorageClient,
            FileStorageProperties storageProperties,
            VerificationProperties verificationProperties,
            ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.profileImageRepository = profileImageRepository;
        this.livenessFileRepository = livenessFileRepository;
        this.attemptRepository = attemptRepository;
        this.fileStorageClient = fileStorageClient;
        this.storageProperties = storageProperties;
        this.verificationProperties = verificationProperties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Uploads a new profile picture.
     *
     * <p>For a verified account the picture is not published yet: it is queued and the comparison runs
     * in the background, which is why the response says {@code pendingVerification}.</p>
     */
    @Transactional
    public ProfileImageResponse replace(String awsId, MultipartFile file) throws FatumUserException {
        User user = getActiveUser(awsId);
        validateImage(file);
        ProfileImage active = profileImageRepository.findActive(user.getAwsId()).orElse(null);
        if (user.getVerificationStatus() != VerificationStatus.VERIFIED) {
            return applyDirectly(user, active, file);
        }
        return queueForComparison(user, active, file);
    }

    public ProfileImageResponse get(String awsId) throws FatumUserException {
        User user = getActiveUser(awsId);
        return current(user.getAwsId());
    }

    /** The published picture, or null when the account has none. Used by the user mapper. */
    public StoredFileResponse find(String awsId) {
        return profileImageRepository.findActive(awsId).map(this::toResponse).orElse(null);
    }

    /**
     * Sets an already stored object as the profile picture, without any comparison.
     *
     * <p>Only used when an administrator verifies an identity manually and uploads the trusted picture
     * themselves. Any picture the user had queued is dropped.</p>
     */
    @Transactional
    public ProfileImage adoptAsProfilePicture(User user, StoredFile storedFile) {
        String awsId = user.getAwsId();
        ProfileImage pending = profileImageRepository.findPending(awsId).orElse(null);
        if (pending != null) {
            String pendingKey = pending.getImageKey();
            profileImageRepository.delete(pending);
            deleteQuietly(pendingKey);
        }
        ProfileImage active = profileImageRepository.findActive(awsId).orElse(null);
        String previousKey = active == null ? null : active.getImageKey();
        ProfileImage image = active;
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

    /** Removes every picture of a user, used by the retention policy when a retry starts over. */
    @Transactional
    public void forget(String awsId) {
        List<ProfileImage> images = List.copyOf(profileImageRepository.findAllByUserAwsId(awsId));
        profileImageRepository.deleteAll(images);
    }

    private ProfileImageResponse current(String awsId) {
        ProfileImage active = profileImageRepository.findActive(awsId).orElse(null);
        ProfileImage pending = profileImageRepository.findPending(awsId).orElse(null);
        return new ProfileImageResponse(
                active == null ? null : toResponse(active),
                pending == null ? null : toResponse(pending),
                pending != null,
                lastChangeOutcome(awsId));
    }

    private VerificationOutcome lastChangeOutcome(String awsId) {
        return attemptRepository.findByUserAwsIdAndTypeOrderByAttemptNumberDesc(awsId, VerificationAttemptType.FACE_ONLY)
                .stream()
                .findFirst()
                .map(VerificationAttempt::getOutcome)
                .orElse(null);
    }

    /** No verification exists yet, so there is nothing to compare the picture with. */
    private ProfileImageResponse applyDirectly(User user, ProfileImage active, MultipartFile file) throws FatumUserException {
        String previousKey = active == null ? null : active.getImageKey();
        StoredFile uploaded = fileStorageClient.upload(file, storageProperties.getProfileImageRoute());
        try {
            ProfileImage image = active;
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
            return new ProfileImageResponse(toResponse(saved), null, false, lastChangeOutcome(user.getAwsId()));
        } catch (RuntimeException exception) {
            deleteQuietly(uploaded.key());
            throw exception;
        }
    }

    /**
     * Queues the picture of a verified account and asks for the comparison.
     *
     * <p>The row is written before the comparison runs, so the event handler finds it. Nothing is
     * published until the comparison says the picture is the same person.</p>
     */
    private ProfileImageResponse queueForComparison(User user, ProfileImage active, MultipartFile file)
            throws FatumUserException {
        String awsId = user.getAwsId();
        LivenessFile reference = livenessFileRepository.findByUserAwsId(awsId).orElse(null);
        if (reference == null) {
            // A verified account with no live reference is broken data. Publishing the picture would
            // leave the account showing any face, so the change is refused until the data is fixed.
            log.error("The verified user {} has no live reference; the picture change was refused", awsId);
            throw new FatumUserException(FatumUserException.PROFILE_REFERENCE_MISSING);
        }
        requireChangesLeftToday(awsId);

        ProfileImage previousPending = profileImageRepository.findPending(awsId).orElse(null);
        String previousPendingKey = previousPending == null ? null : previousPending.getImageKey();
        StoredFile uploaded = fileStorageClient.upload(file, storageProperties.getProfileImageRoute());
        try {
            ProfileImage pending = previousPending;
            if (pending == null) {
                pending = new ProfileImage(
                        uploaded.key(),
                        uploaded.originalFilename(),
                        uploaded.contentType(),
                        uploaded.size(),
                        user,
                        ProfileImageStatus.PENDING);
            } else {
                pending.replace(
                        uploaded.key(),
                        uploaded.originalFilename(),
                        uploaded.contentType(),
                        uploaded.size());
            }
            ProfileImage saved = profileImageRepository.save(pending);
            if (previousPendingKey != null && !previousPendingKey.equals(uploaded.key())) {
                deleteQuietly(previousPendingKey);
            }
            openAttempt(user, uploaded.key());
            eventPublisher.publishEvent(new ProfileImageChangedEvent(awsId, saved.getId()));
            log.info("The profile picture of {} was queued for comparison", awsId);
            return new ProfileImageResponse(
                    active == null ? null : toResponse(active),
                    toResponse(saved),
                    true,
                    VerificationOutcome.PENDING);
        } catch (RuntimeException exception) {
            deleteQuietly(uploaded.key());
            throw exception;
        }
    }

    /** One row per queued picture, so the history says what was asked and how it ended. */
    private void openAttempt(User user, String imageKey) {
        int attemptNumber = (int) attemptRepository.countByUserAwsIdAndType(
                user.getAwsId(),
                VerificationAttemptType.FACE_ONLY) + 1;
        VerificationAttempt attempt = new VerificationAttempt(
                user,
                VerificationAttemptType.FACE_ONLY,
                attemptNumber,
                VerificationBand.MANUAL,
                VerificationOutcome.PENDING,
                VerificationDecision.SYSTEM,
                0d,
                0d,
                0d,
                0d,
                0d,
                "Profile picture change queued for comparison with the live reference",
                "profile-photo-change",
                null,
                null,
                null,
                imageKey);
        attemptRepository.save(attempt);
    }

    private void requireChangesLeftToday(String awsId) throws FatumUserException {
        Instant since = Instant.now().minus(Duration.ofDays(1));
        long queued = attemptRepository.countByUserAwsIdAndTypeAndCreatedAtAfter(
                awsId,
                VerificationAttemptType.FACE_ONLY,
                since);
        if (queued >= verificationProperties.getMaxPendingPhotoChangesPerDay()) {
            throw new FatumUserException(FatumUserException.PROFILE_PHOTO_TOO_MANY_CHANGES);
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
}