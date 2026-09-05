package fatum.controller;

import fatum.dto.ActiveUserResponse;
import fatum.dto.CreateUserRequest;
import fatum.dto.UserMapper;
import fatum.dto.UserResponse;
import fatum.dto.UserStatusResponse;
import fatum.dto.UserUpdateRequest;
import fatum.exception.FatumUserException;
import fatum.model.User;
import fatum.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
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
    private final UserMapper userMapper;

    public UserController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateUserRequest request) throws FatumUserException {
        User newUser = userMapper.toEntity(jwt.getSubject(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(userService.createUser(newUser)));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        return ResponseEntity.ok(toResponse(userService.getUserById(jwt.getSubject())));
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserUpdateRequest request) throws FatumUserException {
        return ResponseEntity.ok(toResponse(userService.updateUser(jwt.getSubject(), request)));
    }

    @PutMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserResponse> updateProfileImage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("image") MultipartFile image) throws FatumUserException, IOException {
        return ResponseEntity.ok(toResponse(userService.updateProfileImage(jwt.getSubject(), image)));
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
    public ResponseEntity<List<UserResponse>> searchUsers(
            @RequestParam @NotBlank String names,
            @RequestParam @NotBlank String surnames) throws FatumUserException {
        List<UserResponse> users = userService.getUsersByName(names, surnames).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/username/{username}")
    public ResponseEntity<UserResponse> getUserByUsername(@PathVariable @NotBlank String username)
            throws FatumUserException {
        return ResponseEntity.ok(toResponse(userService.getUserByUsername(username)));
    }

    private UserResponse toResponse(User user) {
        String profileImageUrl = userService.getProfileImageUrl(user.getProfileImage());
        return userMapper.toResponse(user, profileImageUrl);
    }
}
