package fatum.controller;

import fatum.dto.*;
import fatum.dto.mapper.UserMapper;
import fatum.exception.FatumUserException;
import fatum.model.User;
import fatum.model.constant.VerificationStatus;
import fatum.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * The account of the caller and the public lookups.
 *
 * <p>Every route here reads the account from the token, except the two the sign-up flow uses. The
 * lookups that answer about other people only hand out the accounts that still have access; the
 * administrative view of the same lookups lives in {@code AdminUserController}.</p>
 */
@Tag(name = "Users", description = "Sign-up, own account, and the public directory.")
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

    @Operation(summary = "Validate a sign-up",
            description = "Checks the data the client is about to send: age, and that no other account "
                    + "is using the same identifier, email, phone number, username or document. "
                    + "It writes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The data can be used"),
            @ApiResponse(responseCode = "409", description = "One of the values is already taken",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "The data is not acceptable",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @SecurityRequirements
    @PostMapping("/validate-signup")
    public ResponseEntity<Void> validateSignup(@Valid @RequestBody CreateUserRequest dto) throws FatumUserException {

        User tempUser = UserMapper.toEntity(dto);
        userService.validateUser(tempUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Create the account",
            description = "Called by the sign-up flow once the identity provider confirmed the account. "
                    + "It is not protected by the token: the caller proves itself with the shared "
                    + "secret header, because at this point there is no account to authenticate with.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The account was created"),
            @ApiResponse(responseCode = "403", description = "The shared secret is wrong",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "One of the values is already taken",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<UserResponse> registerUser(
            @Valid @RequestBody CreateUserRequest request,
            @Parameter(description = "Shared secret the sign-up flow sends", required = true)
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
    @Operation(summary = "Read the account of the token",
            description = "Returns the caller's own account, active or not.")
    @ApiResponse(responseCode = "404", description = "The account was never created",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
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
    @Operation(summary = "Find an account by username",
            description = "Only answers for accounts that still have access. A deactivated one is "
                    + "refused with INACTIVE, so the caller knows it exists but has no access.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The account"),
            @ApiResponse(responseCode = "403", description = "The account was deactivated",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that username",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/by-username")
    public ResponseEntity<UserResponse> getUserByUsername(
            @Parameter(description = "Username of the account", required = true)
            @RequestParam("username") String username)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponse(userService.getActiveUserByUsername(username)));
    }

    @Operation(summary = "Find an account by email",
            description = "The same rule as the lookup by username.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The account"),
            @ApiResponse(responseCode = "403", description = "The account was deactivated",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that email",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/by-email")
    public ResponseEntity<UserResponse> getUserByEmail(
            @Parameter(description = "Email of the account", required = true)
            @RequestParam("email") String email)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponse(userService.getActiveUserByEmail(email)));
    }

    @Operation(summary = "Search accounts by name",
            description = "The public directory: only the accounts that still have access.")
    @ApiResponse(responseCode = "200", description = "The matching accounts, possibly none")
    @GetMapping("/search")
    public ResponseEntity<List<UserResponse>> getUsersByName(
            @Parameter(description = "Complete name, or the part of it to look for", required = true)
            @RequestParam("name") String name)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponseList(userService.getActiveUsersByName(name)));
    }

    @Operation(summary = "Read the verification state of an account",
            description = "The state the sign-up set. Every account is born VERIFIED while the document "
                    + "pipeline is off.")
    @ApiResponse(responseCode = "404", description = "No account with that email",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/verification-status")
    public ResponseEntity<VerificationStatus> getVerificationStatus(
            @Parameter(description = "Email of the account", required = true)
            @RequestParam("email") String email)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(userService.getVerificationStatus(email));
    }

    @Operation(summary = "List the professionals",
            description = "The accounts that offer a service, that still have access.")
    @GetMapping("/professionals")
    public  ResponseEntity<List<UserResponse>> getProfessionals()
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponseList(userService.getProfessionals()));
    }

    @Operation(summary = "Update the account of the token",
            description = "Changes the username, the phone number, or adds an address. A field that is "
                    + "not sent is left as it is; a field sent with spaces is cleaned before it is "
                    + "compared, so it cannot take a value that looks the same as another one.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The account as it was stored"),
            @ApiResponse(responseCode = "409", description = "The username or the phone number is taken",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "The address is not acceptable",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/update")
    public ResponseEntity<UserResponse> updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserUpdateRequest request) throws FatumUserException {
        String awsId = jwt.getSubject();
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponse(userService.updateUser(awsId, request)));
    }

    @Operation(summary = "Become a professional, with an address",
            description = "Adds the address of the request and makes it the principal one, then moves "
                    + "the account to PROFESSIONAL. The account has to be verified, and the address is "
                    + "what makes it a professional.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The account is a professional now"),
            @ApiResponse(responseCode = "403", description = "The account is not verified",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The account already has that alias",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "The address is not acceptable",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/upgrade/address")
    public ResponseEntity<UserResponse> becomeProfessionalWithAddress(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody NewAddressRequest request) throws FatumUserException {
        String awsId = jwt.getSubject();
        userService.becomeProfessionalWithAddress(awsId,request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Become a professional",
            description = "Moves the account to PROFESSIONAL using the address it already has. An "
                    + "account without an address cannot be a professional.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The account is a professional now"),
            @ApiResponse(responseCode = "403", description = "The account is not verified",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "The account has no address",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/upgrade")
    public ResponseEntity<UserResponse> becomeProfessional(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        String awsId = jwt.getSubject();
        userService.becomeProfessional(awsId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Go back to client",
            description = "Moves the account to CLIENT. The address is kept: only the role changes.")
    @ApiResponse(responseCode = "204", description = "The account is a client now")
    @PutMapping("/downgrade")
    public ResponseEntity<UserResponse> becomeClient(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        String awsId = jwt.getSubject();
        userService.becomeClient(awsId);
        return ResponseEntity.noContent().build();
    }





}