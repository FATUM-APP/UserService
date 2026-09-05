package fatum.controller;

import fatum.dto.ActiveUserResponse;
import fatum.dto.CreateUserRequest;
import fatum.dto.UserUpdateRequest;
import fatum.model.User;
import fatum.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController unit tests")
class UserControllerTest {

    private static final String AUTH0_ID = "auth0|controller-test";

    @Mock
    private UserService userService;

    private UserController controller;
    private Jwt jwt;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new UserController(userService);
        jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(AUTH0_ID)
                .build();
        user = new User(
                AUTH0_ID,
                "controller@example.com",
                "Camilo",
                "Castaño",
                "+573001112233",
                LocalDate.of(1995, 5, 10));
    }

    @Test
    @DisplayName("Should create the JWT subject and return 201")
    void shouldCreateAuthenticatedUser() throws Exception {
        CreateUserRequest request = new CreateUserRequest(
                "controller@example.com",
                "Camilo",
                "Castaño",
                "+573001112233",
                LocalDate.of(1995, 5, 10));
        when(userService.createUser(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<User> response = controller.createUser(jwt, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).createUser(captor.capture());
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(AUTH0_ID, captor.getValue().getAuth0Id());
        assertEquals("+573001112233", captor.getValue().getPhoneNumber());
    }

    @Test
    @DisplayName("Should get the current user from the JWT subject")
    void shouldGetCurrentUser() throws Exception {
        when(userService.getUserById(AUTH0_ID)).thenReturn(user);

        ResponseEntity<User> response = controller.getCurrentUser(jwt);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(user, response.getBody());
        verify(userService).getUserById(AUTH0_ID);
    }

    @Test
    @DisplayName("Should update the current user")
    void shouldUpdateCurrentUser() throws Exception {
        UserUpdateRequest request = new UserUpdateRequest(
                "updated",
                null,
                null,
                null,
                null,
                null);
        when(userService.updateUser(AUTH0_ID, request)).thenReturn(user);

        ResponseEntity<User> response = controller.updateCurrentUser(jwt, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(user, response.getBody());
    }

    @Test
    @DisplayName("Should deactivate the current user and return 204")
    void shouldDeactivateCurrentUser() throws Exception {
        ResponseEntity<Void> response = controller.deactivateCurrentUser(jwt);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(userService).deactivateUser(AUTH0_ID);
    }

    @Test
    @DisplayName("Should expose the active status by email")
    void shouldReturnActiveStatus() throws Exception {
        when(userService.isUserActiveByEmail("controller@example.com")).thenReturn(true);

        ResponseEntity<ActiveUserResponse> response =
                controller.isUserActive("controller@example.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("controller@example.com", response.getBody().email());
        assertTrue(response.getBody().isActive());
    }
}
