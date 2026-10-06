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


import java.util.List;

import static fatum.dto.mapper.UserMapper.toResponse;
import static fatum.dto.mapper.UserMapper.toResponseList;

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

    /**
     * The account of the token, whether it is active or not: the caller is already authenticated and
     * hiding somebody from themselves protects nothing.
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        String awsId = jwt.getSubject();
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponse(userService.getUserById(awsId)));
    }

    /**
     * The three lookups below answer about other accounts, so they only hand out the ones that are
     * active: a deactivated account is refused with {@code INACTIVE} instead of shown. The
     * administrative view of the same lookups lives in {@code AdminUserController}.
     */
    @GetMapping("/by-username")
    public ResponseEntity<UserResponse> getUserByUsername(@RequestParam("username") String username)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponse(userService.getActiveUserByUsername(username)));
    }

    @GetMapping("/by-email")
    public ResponseEntity<UserResponse> getUserByEmail(@RequestParam("email") String email)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponse(userService.getActiveUserByEmail(email)));
    }

    @GetMapping("/search")
    public ResponseEntity<List<UserResponse>> getUsersByName(@RequestParam("name") String name)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponseList(userService.getActiveUsersByName(name)));
    }

    @GetMapping("/verification-status")
    public ResponseEntity<VerificationStatus> getVerificationStatus(@RequestParam("email") String email)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(userService.getVerificationStatus(email));
    }

    @GetMapping("/professionals")
    public  ResponseEntity<List<UserResponse>> getProfessionals()
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponseList(userService.getProfessionals()));
    }

    @PutMapping("/update")
    public ResponseEntity<UserResponse> updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserUpdateRequest request) throws FatumUserException {
        String awsId = jwt.getSubject();
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponse(userService.updateUser(awsId, request)));
    }

    @PutMapping("/upgrade/address")
    public ResponseEntity<UserResponse> becomeProfessionalWithAddress(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody NewAddressRequest request) throws FatumUserException {
        String awsId = jwt.getSubject();
        userService.becomeProfessionalWithAddress(awsId,request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/upgrade")
    public ResponseEntity<UserResponse> becomeProfessional(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        String awsId = jwt.getSubject();
        userService.becomeProfessional(awsId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/downgrade")
    public ResponseEntity<UserResponse> becomeClient(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        String awsId = jwt.getSubject();
        userService.becomeClient(awsId);
        return ResponseEntity.noContent().build();
    }





}