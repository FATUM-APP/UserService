package fatum.controller;

import fatum.dto.CreateUserRequest;
import fatum.dto.UserMapper;
import fatum.dto.UserResponse;
import fatum.dto.UserStatusResponse;
import fatum.dto.UserUpdateRequest;
import fatum.exception.FatumUserException;
import fatum.model.User;
import fatum.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


import static fatum.dto.UserMapper.toResponse;

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
    public ResponseEntity<Void> validateSignup(@RequestBody CreateUserRequest dto) throws FatumUserException {

        User tempUser = UserMapper.toEntity(dto);
        userService.validateUser(tempUser);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> registerUser(
            @RequestBody CreateUserRequest request,
            @RequestHeader("Lambda-Secret") String receivedSecret
            ) throws FatumUserException {
        if (!lambdaSecret.equals(receivedSecret)) throw new FatumUserException(FatumUserException.FORBIDDEN);
        User newUser = UserMapper.toEntity(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(userService.createUser(newUser)));
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
            @RequestBody UserUpdateRequest request) throws FatumUserException {
        String awsId = jwt.getSubject();
        User user = userService.updateUser(
                awsId,
                request.username(),
                request.phoneNumber(),
                request.role(),
                request.city()
        );

        return ResponseEntity.status(HttpStatus.OK).body(toResponse(user));
    }

    @GetMapping("/me/authenticated")
    public ResponseEntity<UserStatusResponse> isCurrentUserAuthenticated(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        boolean authenticated = userService.userIsAuthenticated(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.OK).body(new UserStatusResponse(authenticated));
    }

    @PutMapping("/deactivate")
    public ResponseEntity<Void> deactivateCurrentUser(@RequestParam String email)
            throws FatumUserException {
        userService.deactivateUser(email);
        return ResponseEntity.noContent().build();
    }


}
