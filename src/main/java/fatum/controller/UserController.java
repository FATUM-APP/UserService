package fatum.controller;

import fatum.dto.ActiveUserResponse;
import fatum.dto.CreateUserRequest;
import fatum.dto.UserStatusResponse;
import fatum.dto.UserUpdateRequest;
import fatum.exception.FatumUserException;
import fatum.model.User;
import fatum.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/users")
@Validated
public class UserController {

    private final UserService userService;

    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<User> createUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateUserRequest request) throws FatumUserException {
        User newUser = new User(
                jwt.getSubject(),
                request.email(),
                request.names(),
                request.surnames(),
                request.birthDate());
        return ResponseEntity.status(201).body(userService.createUser(newUser));
    }

    @GetMapping("/me")
    public ResponseEntity<User> getCurrentUser(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        User user = userService.getUserById(jwt.getSubject());
        enrichProfileImage(user);
        return ResponseEntity.ok(user);
    }

    @PutMapping("/me")
    public ResponseEntity<User> updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserUpdateRequest request) throws FatumUserException {
        User updated = userService.updateUser(jwt.getSubject(), request);
        enrichProfileImage(updated);
        return ResponseEntity.ok(updated);
    }

    @PutMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<User> updateProfileImage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("image") MultipartFile image) throws FatumUserException, IOException {
        User updated = userService.updateProfileImage(jwt.getSubject(), image);
        enrichProfileImage(updated);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/me/authenticate-document")
    public ResponseEntity<UserStatusResponse> authenticateDocument(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        boolean authenticated = userService.authenticateUserDocument(jwt.getSubject());
        return ResponseEntity.ok(new UserStatusResponse(jwt.getSubject(), authenticated));
    }

    @GetMapping("/me/authenticated")
    public ResponseEntity<UserStatusResponse> isCurrentUserAuthenticated(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        boolean authenticated = userService.userIsAuthenticated(jwt.getSubject());
        return ResponseEntity.ok(new UserStatusResponse(jwt.getSubject(), authenticated));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deactivateCurrentUser(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        userService.deactivateUser(jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/active")
    public ResponseEntity<ActiveUserResponse> isUserActive(
            @RequestParam @NotBlank @Email String email) throws FatumUserException {
        return ResponseEntity.ok(new ActiveUserResponse(email, userService.isUserActiveByEmail(email)));
    }

    @GetMapping("/search")
    public ResponseEntity<List<User>> searchUsers(
            @RequestParam @NotBlank String names,
            @RequestParam @NotBlank String surnames) throws FatumUserException {
        List<User> users = userService.getUsersByName(names, surnames);
        users.forEach(this::enrichProfileImage);
        return ResponseEntity.ok(users);
    }

    @GetMapping("/username/{username}")
    public ResponseEntity<User> getUserByUsername(@PathVariable String username)
            throws FatumUserException {
        User user = userService.getUserByUsername(username);
        enrichProfileImage(user);
        return ResponseEntity.ok(user);
    }

    private void enrichProfileImage(User user) {
        if (user.getProfileImage() != null) {
            userService.setProfileImageUrl(user.getProfileImage());
        }
    }
}
