package fatum.service;

import fatum.dto.UserUpdateRequest;
import fatum.exception.FatumUserException;
import fatum.model.DocumentType;
import fatum.model.User;
import fatum.model.UserRole;
import fatum.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.net.URL;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService unit tests")
class UserServiceTest {

    private static final String AUTH0_ID = "auth0|12345";
    private static final String EMAIL = "test@example.com";

    @Mock
    private UserRepository userRepository;

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    private UserService userService;
    private User testUser;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, s3Client, s3Presigner, "fatum-profile-images");
        testUser = new User(
                AUTH0_ID,
                EMAIL,
                "Camilo",
                "Castaño",
                "+573183074075",
                LocalDate.now().minusYears(25));
        testUser.setUsername("ccastano41");
    }

    @Test
    @DisplayName("Should create an adult user")
    void shouldCreateUserSuccessfully() throws FatumUserException {
        when(userRepository.save(testUser)).thenReturn(testUser);

        User result = userService.createUser(testUser);

        assertEquals(testUser, result);
        verify(userRepository).findByAuth0Id(AUTH0_ID);
        verify(userRepository).findByEmail(EMAIL);
        verify(userRepository).save(testUser);
    }

    @Test
    @DisplayName("Should reject an underage user")
    void shouldRejectUnderageUser() {
        User underage = new User(
                "auth0|minor",
                "minor@example.com",
                "Minor",
                "User",
                "+573000000000",
                LocalDate.now().minusYears(18).plusDays(1));

        FatumUserException exception = assertThrows(
                FatumUserException.class,
                () -> userService.createUser(underage));

        assertEquals(FatumUserException.UNDERAGE_USER, exception.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should reject an existing Auth0 subject")
    void shouldRejectExistingAuth0Id() {
        when(userRepository.findByAuth0Id(AUTH0_ID)).thenReturn(testUser);

        FatumUserException exception = assertThrows(
                FatumUserException.class,
                () -> userService.createUser(testUser));

        assertEquals(FatumUserException.USER_ALREADY_EXISTS, exception.getMessage());
        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should reject an existing email")
    void shouldRejectExistingEmail() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(testUser);

        FatumUserException exception = assertThrows(
                FatumUserException.class,
                () -> userService.createUser(testUser));

        assertEquals(FatumUserException.EMAIL_EXISTS, exception.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should update username and phone")
    void shouldUpdateUsernameAndPhone() throws FatumUserException {
        when(userRepository.findByAuth0Id(AUTH0_ID)).thenReturn(testUser);
        when(userRepository.save(testUser)).thenReturn(testUser);

        User result = userService.updateUser(
                AUTH0_ID,
                new UserUpdateRequest(
                        "newusername",
                        "+573001112233",
                        null,
                        null,
                        null,
                        null));

        assertEquals("newusername", result.getUsername());
        assertEquals("+573001112233", result.getPhoneNumber());
        verify(userRepository).findByUsername("newusername");
        verify(userRepository).findByPhoneNumber("+573001112233");
    }

    @Test
    @DisplayName("Should reject a duplicate username")
    void shouldRejectDuplicateUsername() {
        User otherUser = new User(
                "auth0|other",
                "other@example.com",
                "Other",
                "User",
                "+573009999999",
                LocalDate.now().minusYears(30));
        when(userRepository.findByAuth0Id(AUTH0_ID)).thenReturn(testUser);
        when(userRepository.findByUsername("taken")).thenReturn(otherUser);

        FatumUserException exception = assertThrows(
                FatumUserException.class,
                () -> userService.updateUser(
                        AUTH0_ID,
                        new UserUpdateRequest("taken", null, null, null, null, null)));

        assertEquals(FatumUserException.USERNAME_EXISTS, exception.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should promote a user to professional when city is supplied")
    void shouldPromoteProfessionalWithCity() throws FatumUserException {
        when(userRepository.findByAuth0Id(AUTH0_ID)).thenReturn(testUser);
        when(userRepository.save(testUser)).thenReturn(testUser);

        User updated = userService.updateUser(
                AUTH0_ID,
                new UserUpdateRequest(null, null, UserRole.PROFESSIONAL, null, null, "Bogotá"));

        assertEquals(UserRole.PROFESSIONAL, updated.getRole());
        assertEquals("Bogotá", updated.getCity());
    }

    @Test
    @DisplayName("Should require a city for professional users")
    void shouldRequireCityForProfessional() {
        when(userRepository.findByAuth0Id(AUTH0_ID)).thenReturn(testUser);

        FatumUserException exception = assertThrows(
                FatumUserException.class,
                () -> userService.updateUser(
                        AUTH0_ID,
                        new UserUpdateRequest(null, null, UserRole.PROFESSIONAL, null, null, null)));

        assertEquals(FatumUserException.PROFESSIONAL_CITY, exception.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should require document and document type together")
    void shouldRequireDocumentPair() {
        when(userRepository.findByAuth0Id(AUTH0_ID)).thenReturn(testUser);

        FatumUserException exception = assertThrows(
                FatumUserException.class,
                () -> userService.updateUser(
                        AUTH0_ID,
                        new UserUpdateRequest(null, null, null, "1000271422", null, null)));

        assertEquals(FatumUserException.DOCUMENT_TYPE_REQUIRED, exception.getMessage());
    }

    @Test
    @DisplayName("Should keep a previously assigned document immutable")
    void shouldRejectDocumentMutation() throws FatumUserException {
        testUser.setDocument("1000271422", DocumentType.ID);
        when(userRepository.findByAuth0Id(AUTH0_ID)).thenReturn(testUser);

        FatumUserException exception = assertThrows(
                FatumUserException.class,
                () -> userService.updateUser(
                        AUTH0_ID,
                        new UserUpdateRequest(null, null, null, "999999999", DocumentType.ID, null)));

        assertEquals(FatumUserException.DOCUMENT_NOT_MUTABLE, exception.getMessage());
    }

    @Test
    @DisplayName("Should deactivate the user")
    void shouldDeactivateUser() throws FatumUserException {
        when(userRepository.findByAuth0Id(AUTH0_ID)).thenReturn(testUser);

        userService.deactivateUser(AUTH0_ID);

        assertFalse(testUser.isActive());
        verify(userRepository).save(testUser);
    }

    @Test
    @DisplayName("Should check whether a user is active by email")
    void shouldCheckActiveUserByEmail() throws FatumUserException {
        when(userRepository.findByEmail(EMAIL)).thenReturn(testUser);

        assertTrue(userService.isUserActiveByEmail(EMAIL));
    }

    @Test
    @DisplayName("Should search by names and surnames")
    void shouldSearchByName() throws FatumUserException {
        when(userRepository.findByNamesIgnoreCaseAndSurnamesIgnoreCase("Camilo", "Castaño"))
                .thenReturn(List.of(testUser));

        assertEquals(List.of(testUser), userService.getUsersByName("Camilo", "Castaño"));
    }

    @Test
    @DisplayName("Should upload and replace a profile image in S3")
    void shouldUpdateProfileImage() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "avatar.png",
                "image/png",
                new byte[]{1, 2, 3});
        when(userRepository.findByAuth0Id(AUTH0_ID)).thenReturn(testUser);
        when(userRepository.save(testUser)).thenReturn(testUser);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());

        User updated = userService.updateProfileImage(AUTH0_ID, image);

        assertNotNull(updated.getProfileImage());
        assertTrue(updated.getProfileImage().getImageKey().startsWith("profile-images/"));
        assertTrue(updated.getProfileImage().getImageKey().endsWith("-avatar.png"));
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("Should delete the previous profile image after replacement")
    void shouldDeletePreviousProfileImage() throws Exception {
        testUser.setProfileImage("profile-images/old.png");
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "new.png",
                "image/png",
                new byte[]{4, 5, 6});
        when(userRepository.findByAuth0Id(AUTH0_ID)).thenReturn(testUser);
        when(userRepository.save(testUser)).thenReturn(testUser);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());

        userService.updateProfileImage(AUTH0_ID, image);

        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("Should generate a 15-minute S3 presigned URL")
    void shouldGenerateProfileImageUrl() throws Exception {
        testUser.setProfileImage("profile-images/avatar.png");
        URL expectedUrl = URI.create("https://s3.amazonaws.com/fatum-profile-images/profile-images/avatar.png").toURL();
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(expectedUrl);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        userService.setProfileImageUrl(testUser.getProfileImage());

        assertEquals(expectedUrl.toString(), testUser.getProfileImage().getPresignedUrl());
        verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }
}
