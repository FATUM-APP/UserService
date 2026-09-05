package fatum.service;

import fatum.dto.UserUpdateRequest;
import fatum.exception.FatumUserException;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.UserRole;
import fatum.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;

    @Autowired
    public UserService(
            UserRepository userRepository,
            S3Client s3Client,
            S3Presigner s3Presigner,
            @Value("${aws.s3.bucket}") String bucketName) {
        this.userRepository = userRepository;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucketName = bucketName;
    }

    @Transactional
    public User createUser(User newUser) throws FatumUserException {
        validateNewUser(newUser);
        return userRepository.save(newUser);
    }

    public User getUserById(String auth0Id) throws FatumUserException {
        if (auth0Id == null || auth0Id.isBlank()) throw new FatumUserException(FatumUserException.NULL_VALUE);

        User user = userRepository.findByAuth0Id(auth0Id);
        if (user == null) throw new FatumUserException(FatumUserException.USER_NOT_FOUND);

        return user;
    }

    public User getUserByUsername(String username) throws FatumUserException {
        if (username == null || username.isBlank()) throw new FatumUserException(FatumUserException.NULL_VALUE);

        User user = userRepository.findByUsername(username);
        if (user == null) throw new FatumUserException(FatumUserException.USER_NOT_FOUND);

        return user;
    }

    public List<User> getUsersByName(String names, String surnames) throws FatumUserException {
        if (names == null || names.isBlank() || surnames == null || surnames.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        return userRepository.findByNamesIgnoreCaseAndSurnamesIgnoreCase(names, surnames);
    }

    @Transactional
    public User updateUser(String auth0Id, UserUpdateRequest update) throws FatumUserException {
        if (update == null || auth0Id == null || auth0Id.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }

        User existingUser = getUserById(auth0Id);

        existingUser.setUsername(update.username());
        updatePhoneNumber(existingUser, normalize(update.phoneNumber()));
        updateRoleAndCity(existingUser, update);
        updateDocument(existingUser, update);

        return userRepository.save(existingUser);
    }

    @Transactional
    public User updateProfileImage(String auth0Id, MultipartFile newImage)
            throws FatumUserException, IOException {
        User user = getUserById(auth0Id);
        validateImage(newImage);

        String previousImageKey = user.getProfileImage() == null
                ? null
                : user.getProfileImage().getImageKey();
        String imageKey = uploadImage(newImage);
        user.setProfileImage(imageKey);
        User savedUser = userRepository.save(user);

        if (previousImageKey != null && !previousImageKey.equals(imageKey)) {
            deleteImage(previousImageKey);
        }
        return savedUser;
    }

    @Transactional
    public void deactivateUser(String auth0Id) throws FatumUserException {
        User user = getUserById(auth0Id);
        user.setActive(false);
        userRepository.save(user);
    }

    public boolean userIsAuthenticated(String auth0Id) throws FatumUserException {
        return getUserById(auth0Id).isAuthenticated();
    }

    public boolean isUserActiveByEmail(String email) throws FatumUserException {
        if (email == null || email.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user.isActive();
    }

    public void setProfileImageUrl(ProfileImage profileImage) {
        if (profileImage == null || profileImage.getImageKey() == null) {
            return;
        }
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(profileImage.getImageKey())
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(getObjectRequest)
                .build();
        profileImage.setPresignedUrl(s3Presigner.presignGetObject(presignRequest).url().toString());
    }

    private void updateUsername(User user, String username) throws FatumUserException {
        if (username == null) {
            return;
        }
        if (!username.equals(user.getUsername())) {
            User conflict = userRepository.findByUsername(username);
            if (conflict != null && !conflict.getAuth0Id().equals(user.getAuth0Id())) {
                throw new FatumUserException(FatumUserException.USERNAME_EXISTS);
            }
        }
        user.setUsername(username);
    }

    private void updatePhoneNumber(User user, String phoneNumber) throws FatumUserException {
        if (phoneNumber == null) {
            return;
        }
        if (!phoneNumber.equals(user.getPhoneNumber())) {
            User conflict = userRepository.findByPhoneNumber(phoneNumber);
            if (conflict != null && !conflict.getAuth0Id().equals(user.getAuth0Id())) {
                throw new FatumUserException(FatumUserException.PHONE_EXISTS);
            }
        }
        user.setPhoneNumber(phoneNumber);
    }

    private void updateRoleAndCity(User user, UserUpdateRequest update) throws FatumUserException {
        String city = normalize(update.city());
        UserRole targetRole = update.role() == null ? user.getRole() : update.role();
        String effectiveCity = city == null ? user.getCity() : city;

        if (targetRole == UserRole.PROFESSIONAL && effectiveCity == null) {
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        }
        if (city != null) {
            user.setCity(city);
        }
        if (update.role() != null) {
            user.setRole(update.role());
        }
    }

    private void updateDocument(User user, UserUpdateRequest update) throws FatumUserException {
        String document = normalize(update.document());
        boolean hasDocument = document != null;
        boolean hasDocumentType = update.documentType() != null;

        if (hasDocument != hasDocumentType) {
            throw new FatumUserException(FatumUserException.DOCUMENT_TYPE_REQUIRED);
        }
        if (!hasDocument) {
            return;
        }
        if (document.equals(user.getDocument()) && update.documentType() == user.getDocumentType()) {
            return;
        }
        if (user.getDocument() != null) {
            throw new FatumUserException(FatumUserException.DOCUMENT_NOT_MUTABLE);
        }

        User conflict = userRepository.findByDocument(document);
        if (conflict != null && !conflict.getAuth0Id().equals(user.getAuth0Id())) {
            throw new FatumUserException(FatumUserException.DOCUMENT_EXISTS);
        }
        user.setDocument(document, update.documentType());
    }

    private void validateNewUser(User user) throws FatumUserException {

        // Check for null values
        if (user == null
                || user.getAuth0Id() == null || user.getAuth0Id().isBlank()
                || user.getEmail() == null || user.getEmail().isBlank()
                || user.getNames() == null || user.getNames().isBlank()
                || user.getSurnames() == null || user.getSurnames().isBlank()
                || user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()
                || user.getBirthDate() == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }

        //Check for uniqueness
        if (userRepository.findByAuth0Id(user.getAuth0Id()) != null)
            throw new FatumUserException(FatumUserException.USER_ALREADY_EXISTS);

        if (userRepository.findByEmail(user.getEmail()) != null)
            throw new FatumUserException(FatumUserException.EMAIL_EXISTS);

        if (userRepository.findByPhoneNumber(user.getPhoneNumber()) != null)
            throw new FatumUserException(FatumUserException.PHONE_EXISTS);

        // Check for age
        if (user.getBirthDate().isAfter(LocalDate.now().minusYears(18)))
            throw new FatumUserException(FatumUserException.UNDERAGE_USER);

    }

    private void validateImage(MultipartFile image) throws FatumUserException {
        if (image == null || image.isEmpty() || image.getOriginalFilename() == null) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE);
        }
        if (image.getContentType() == null || !image.getContentType().startsWith("image/")) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE_TYPE);
        }
    }

    private String uploadImage(MultipartFile image) throws IOException {
        String originalFilename = StringUtils.cleanPath(image.getOriginalFilename());
        String objectName = "profile-images/" + UUID.randomUUID() + "-" + originalFilename;
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .contentType(image.getContentType())
                .build();
        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(image.getBytes()));
        return objectName;
    }

    private void deleteImage(String imageKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(imageKey)
                .build());
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
