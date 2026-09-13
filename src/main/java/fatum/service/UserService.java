package fatum.service;

import fatum.dto.UserUpdateRequest;
import fatum.exception.FatumUserException;
import fatum.model.DocumentType;
import fatum.model.ProfileImage;
import fatum.model.User;
import fatum.model.UserRole;
import fatum.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
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
        if (auth0Id == null || auth0Id.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = userRepository.findByAuth0Id(auth0Id);
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User getUserByDocument(String document) throws FatumUserException {
        if (document == null || document.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = userRepository.findByAuth0Id(document.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }


    public User getUserByUsername(String username) throws FatumUserException {
        if (username == null || username.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = userRepository.findByUsernameIgnoreCase(username.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User getUserByEmail(String email) throws FatumUserException {
        if (email == null || email.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = userRepository.findByEmailIgnoreCase(email.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User getUserByPhoneNumber(String phoneNumber) throws FatumUserException {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = userRepository.findByPhoneNumber(phoneNumber.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }


    public List<User> getUsersByName(String names, String surnames) throws FatumUserException {
        if (names == null || names.isBlank() || surnames == null || surnames.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        return userRepository.findByNamesIgnoreCaseAndSurnamesIgnoreCase(names.trim(), surnames.trim());
    }

    @Transactional
    public User updateUser(String auth0Id, UserUpdateRequest update) throws FatumUserException {
        if (update == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }

        User existingUser = getUserById(auth0Id);
        updateUsername(existingUser, update.username());
        updatePhoneNumber(existingUser, update.phoneNumber());
        updateRoleAndCity(existingUser, update);
        updateDocument(existingUser, update.document(), update.documentType());
        return userRepository.save(existingUser);
    }

    @Transactional
    public User updateProfileImage(String auth0Id, MultipartFile newImage)
            throws FatumUserException, IOException {

        // 1. Validar y obtener el nombre seguro una sola vez
        String safeFilename = validateImage(newImage);

        // 2. Buscar al usuario después de validar la entrada
        User user = getUserById(auth0Id);

        // 3. Guardar la clave anterior para eliminarla después
        String previousImageKey = user.getProfileImage() == null
                ? null
                : user.getProfileImage().getImageKey();

        // 4. Subir la nueva imagen usando el nombre ya validado
        String imageKey = uploadImage(newImage, safeFilename);

        // 5. Actualizar la relación del usuario con la nueva imagen
        user.setProfileImage(imageKey);
        User savedUser = userRepository.save(user);

        // 6. Eliminar la imagen anterior sólo después de guardar correctamente
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


    public boolean isUserActive(String email, String username) throws FatumUserException {
        if (email == null || email.isBlank() && (username == null || username.isBlank())) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        User user = email == null || email.isBlank() ? getUserByUsername(username) : getUserByEmail(email);
        return user.isActive();
    }

    public String getProfileImageUrl(ProfileImage profileImage) {
        if (profileImage == null || profileImage.getImageKey() == null) {
            return null;
        }
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(profileImage.getImageKey())
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(getObjectRequest)
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    private void updateUsername(User user, String username) throws FatumUserException {
        String targetUsername = username == null || username.isBlank() ? user.getUsername() : username;

        User conflict = getUserByUsername(targetUsername);
        if (conflict != null && !conflict.equals(user)) {
            throw new FatumUserException(FatumUserException.USERNAME_EXISTS);
        }
        user.setUsername(targetUsername);
    }

    private void updatePhoneNumber(User user, String phoneNumber) throws FatumUserException {
        String targetPhoneNumber = phoneNumber == null || phoneNumber.isBlank() ? user.getPhoneNumber() : phoneNumber;

        User conflict = getUserByPhoneNumber(targetPhoneNumber);
        if (conflict != null && !conflict.equals(user)) {
            throw new FatumUserException(FatumUserException.PHONE_EXISTS);
        }
        user.setPhoneNumber(targetPhoneNumber);
    }

    private void updateRoleAndCity(User user, UserUpdateRequest update) throws FatumUserException {
        String city = update.city();
        UserRole targetRole = update.role() == null ? user.getRole() : update.role();
        String effectiveCity = city == null || city.isBlank() ? user.getCity() : city;

        user.setCity(effectiveCity);
        user.setRole(targetRole);
    }

    private void updateDocument(User user, String document, DocumentType documentType) throws FatumUserException {
        String targetDocument = document == null || document.isBlank() ? user.getDocument() : document;
        DocumentType targetDocumentType = documentType == null ? user.getDocumentType() : documentType;

        User conflict = getUserByDocument(targetDocument);
        if (conflict != null && !conflict.equals(user)) {
            throw new FatumUserException(FatumUserException.PHONE_EXISTS);
        }
        user.setDocument(targetDocument, targetDocumentType);
    }




    private void validateNewUser(User user) throws FatumUserException {

        // Check for null values
        if (user == null
                || user.getAuth0Id() == null || user.getAuth0Id().isBlank()
                || user.getEmail() == null || user.getEmail().isBlank()
                || user.getName() == null || user.getName().isBlank()
                || user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()
                || user.getBirthDate() == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }

        //Check for uniqueness
        if (getUserById(user.getAuth0Id()) != null)
            throw new FatumUserException(FatumUserException.USER_ALREADY_EXISTS);

        if (getUserByEmail(user.getEmail()) != null)
            throw new FatumUserException(FatumUserException.EMAIL_EXISTS);

        if (getUserByPhoneNumber(user.getPhoneNumber()) != null)
            throw new FatumUserException(FatumUserException.PHONE_EXISTS);

        if(user.getDocument() != null && getUserByDocument(user.getDocument()) != null)
            throw new FatumUserException(FatumUserException.DOCUMENT_EXISTS);

        // Check for age
        if (user.getBirthDate().isAfter(LocalDate.now().minusYears(18)))
            throw new FatumUserException(FatumUserException.UNDERAGE_USER);
    }

    private String validateImage(MultipartFile image)
            throws FatumUserException {

        if (image == null || image.isEmpty()) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE);
        }

        String originalFilename = image.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE);
        }

        String contentType = image.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE_TYPE);
        }

        String cleanedFilename = StringUtils.cleanPath(originalFilename);
        String safeFilename = StringUtils.getFilename(cleanedFilename);

        if (safeFilename == null || safeFilename.isBlank()
                || safeFilename.equals(".")
                || safeFilename.equals("..")) {
            throw new FatumUserException(FatumUserException.INVALID_IMAGE);
        }

        return safeFilename;
    }


    private String uploadImage(MultipartFile image, String safeFilename)
            throws IOException {

        String objectName = "profile-images/"
                + UUID.randomUUID()
                + "-"
                + safeFilename;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .contentType(image.getContentType())
                .build();

        s3Client.putObject(
                putObjectRequest,
                RequestBody.fromBytes(image.getBytes()));

        return objectName;
    }



    private void deleteImage(String imageKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(imageKey)
                .build());
    }

}
