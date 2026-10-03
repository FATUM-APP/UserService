package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.repository.ProfileImageRepository;
import fatum.repository.UserRepository;
import fatum.storage.FileStorageClient;
import fatum.storage.FileStorageProperties;
import fatum.storage.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Profile picture of an account.
 *
 * <p>A picture is replaced, never versioned: the object is uploaded first, the row is updated, and
 * only then the previous object is deleted. Uploading before deleting is what makes the operation
 * safe, because a failure in the middle leaves the new picture in place instead of none at all.</p>
 */
@Service
public class ProfileImageService {

    private final UserRepository userRepository;
    private final ProfileImageRepository profileImageRepository;
    private final FileStorageClient storage;
    private final FileStorageProperties storageProperties;

    public ProfileImageService(
            UserRepository userRepository,
            ProfileImageRepository profileImageRepository,
            FileStorageClient storage,
            FileStorageProperties storageProperties) {
        this.userRepository = userRepository;
        this.profileImageRepository = profileImageRepository;
        this.storage = storage;
        this.storageProperties = storageProperties;
    }

    /**
     * Stores a new profile picture for the account, replacing whatever was there before.
     *
     * @param auth0Id Cognito subject of the account
     * @param file    image to store
     * @return metadata of the stored picture, including a temporary download URL
     */
    @Transactional
    public StoredFileResponse replace(String auth0Id, MultipartFile file)
            throws FatumUserException {
        User user = getActiveUser(auth0Id);
        validate(file);
        ProfileImage current = profileImageRepository.findByUserAwsId(auth0Id).orElse(null);
        String previousKey = current == null ? null : current.getImageKey();

        StoredFile uploaded = storage.upload(file, storageProperties.getProfileImageRoute());

        try {
            if (current == null) {
                current = new ProfileImage(
                        uploaded.key(),
                        uploaded.originalFilename(),
                        uploaded.contentType(),
                        uploaded.size(),
                        user);
            } else {
                current.replace(
                        uploaded.key(),
                        uploaded.originalFilename(),
                        uploaded.contentType(),
                        uploaded.size());
            }

            ProfileImage saved = profileImageRepository.save(current);
            if (previousKey != null && !previousKey.equals(uploaded.key())) {
                storage.delete(storageProperties.getProfileImageRoute(), previousKey);
            }
            return toResponse(saved);
        } catch (RuntimeException exception) {
            storage.delete(storageProperties.getProfileImageRoute(), uploaded.key());
            throw exception;
        }
    }


    public StoredFileResponse get(String auth0Id) throws FatumUserException {
        getActiveUser(auth0Id);
        ProfileImage image = profileImageRepository.findByUserAwsId(auth0Id)
                .orElseThrow(() -> new FatumUserException(FatumUserException.FILE_NOT_FOUND));
        return toResponse(image);
    }


    public StoredFileResponse find(String auth0Id) {
        return profileImageRepository.findByUserAwsId(auth0Id)
                .map(this::toResponse)
                .orElse(null);
    }

    private StoredFileResponse toResponse(ProfileImage image) {
        return new StoredFileResponse(
                image.getId(),
                image.getOriginalFilename(),
                image.getContentType(),
                image.getFileSize(),
                storage.presignedUrl(storageProperties.getProfileImageRoute(), image.getImageKey()),
                image.getCreatedAt(),
                image.getUpdatedAt());
    }

    private User getActiveUser(String auth0Id) throws FatumUserException {
        validateAuth0Id(auth0Id);
        User user = userRepository.findByAwsId(auth0Id);
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    private void validateAuth0Id(String auth0Id) throws FatumUserException {
        if (auth0Id == null || auth0Id.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
    }

    private void validate(MultipartFile file) throws FatumUserException {
        if (file == null || file.isEmpty()) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE);
        }
        String originalFilename = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE);
        }
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE_TYPE);
        }
    }
}