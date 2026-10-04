package fatum.controller;

import fatum.dto.*;
import fatum.dto.mapper.UserMapper;
import fatum.exception.FatumUserException;
import fatum.model.User;
import fatum.model.constant.VerificationStatus;
import fatum.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


import static fatum.dto.mapper.UserMapper.toResponse;

@RestController
@RequestMapping("/users")
@Validated
public class UserController {

    private final UserService userService;

    @Value("${app.lambda.secret}")
    private String lambdaSecret;

    public UserController(
            UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/validate-signup")
    public ResponseEntity<Void> validateSignup(@Valid @RequestBody CreateUserRequest dto) throws FatumUserException {

        User tempUser = UserMapper.toEntity(dto);
        userService.validateUser(tempUser);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> registerUser(
            @Valid @RequestBody CreateUserRequest request,
            @RequestHeader("Lambda-Secret") String receivedSecret
            ) throws FatumUserException {
        if (!lambdaSecret.equals(receivedSecret)) throw new FatumUserException(FatumUserException.FORBIDDEN);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(userService.createUser(request)));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        String awsId = jwt.getSubject();
        return ResponseEntity.ok(toResponse(userService.getUserById(awsId)));
    }

    @PutMapping("/update")
    public ResponseEntity<UserResponse> updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserUpdateRequest request) throws FatumUserException {
        String awsId = jwt.getSubject();
        User user = userService.updateUser(awsId, request);

        return ResponseEntity.status(HttpStatus.OK).body(toResponse(user));
    }

    @GetMapping("/me/authenticated")
    public ResponseEntity<UserStatusResponse> isCurrentUserAuthenticated(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        VerificationStatus status = userService.getVerificationStatus(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.OK)
                .body(new UserStatusResponse(status, status == VerificationStatus.VERIFIED));
    }

    @PutMapping("/deactivate")
    public ResponseEntity<Void> deactivateCurrentUser(@RequestParam String email)
            throws FatumUserException {
        userService.deactivateUser(email);
        return ResponseEntity.noContent().build();
    }


}
