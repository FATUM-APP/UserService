package fatum.service;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.repository.ProfileImageRepository;
import fatum.repository.UserRepository;
import fatum.storage.S3FileStorage;
import fatum.storage.StoredObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
public class ProfileImageService {

    private static final String OBJECT_PREFIX = "profile-images";

    private final UserRepository userRepository;
    private final ProfileImageRepository profileImageRepository;
    private final S3FileStorage storage;
    private final String bucketName;

    public ProfileImageService(
            UserRepository userRepository,
            ProfileImageRepository profileImageRepository,
            S3FileStorage storage,
            @Value("${aws.s3.profile-images-bucket}") String bucketName) {
        this.userRepository = userRepository;
        this.profileImageRepository = profileImageRepository;
        this.storage = storage;
        this.bucketName = bucketName;
    }

    @Transactional
    public StoredFileResponse replace(String auth0Id, MultipartFile file)
            throws FatumUserException, IOException {
        User user = getActiveUser(auth0Id);
        ValidatedFile validatedFile = validate(file);
        ProfileImage current = profileImageRepository.findByUserAwsId(auth0Id).orElse(null);
        String previousKey = current == null ? null : current.getImageKey();

        StoredObject uploaded = storage.upload(
                file,
                bucketName,
                OBJECT_PREFIX,
                validatedFile.safeFilename(),
                validatedFile.contentType());

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
                storage.delete(bucketName, previousKey);
            }
            return toResponse(saved);
        } catch (RuntimeException exception) {
            storage.delete(bucketName, uploaded.key());
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
                storage.presignedDownloadUrl(bucketName, image.getImageKey()),
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

    private ValidatedFile validate(MultipartFile file) throws FatumUserException {
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
        try {
            return new ValidatedFile(storage.sanitizeFilename(originalFilename), contentType);
        } catch (IllegalArgumentException exception) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE);
        }
    }

    private record ValidatedFile(String safeFilename, String contentType) {
    }
}
