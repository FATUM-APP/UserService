package fatum.controller;

import fatum.dto.UserResponse;
import fatum.dto.ApiError;
import fatum.exception.FatumUserException;
import fatum.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static fatum.dto.mapper.UserMapper.toResponse;
import static fatum.dto.mapper.UserMapper.toResponseList;

/**
 * The operations only an administrator may run.
 *
 * <p>The group arrives in the {@code cognito:groups} claim and the security configuration turns it
 * into an authority with the {@code ROLE_} prefix, which is what {@code hasRole} reads here. The
 * same rule is repeated at the URL level in the filter chain on purpose: if somebody reorders those
 * matchers, this annotation is still the one that decides.</p>
 */
@Tag(name = "Administration", description = "The operations only an administrator may run.")
@RestController
@RequestMapping("/admin")
@Validated
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Reads any account, active or not.
     *
     * <p>It is the administrative view of the same lookup the public endpoints offer, which refuse a
     * deactivated account. The response carries {@code isActive}, so the panel can show the state.</p>
     */
    @Operation(summary = "Find any account by email",
            operationId = "adminFindUserByEmail",
            description = "The administrative view of the public lookup: it does not refuse a "
                    + "deactivated account, and the response carries its state.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The account, active or not"),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that email",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping
    public ResponseEntity<UserResponse> getUserByEmail(
            @Parameter(description = "Email of the account", required = true)
            @RequestParam("email") String email)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponse(userService.getUserByEmail(email)));
    }

    /** Searches by name over every account, active or not. */
    @Operation(summary = "Search any account by name",
            operationId = "adminSearchUsersByName",
            description = "Searches over every account. A deactivated one is part of the answer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The matching accounts, possibly none"),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/search")
    public ResponseEntity<List<UserResponse>> getUsersByName(
            @Parameter(description = "Complete name, or the part of it to look for", required = true)
            @RequestParam("name") String name)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponseList(userService.getUsersByName(name)));
    }

    /** Takes away the access of an account and every session it had open. */
    @Operation(summary = "Deactivate an account",
            description = "Takes away the access of the account. The local state is written first and "
                    + "the event announcing it follows: the consumer of the event is the one that takes "
                    + "the access away in the user pool, so a pool outage cannot block the deactivation.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The account was deactivated"),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that email",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/deactivate")
    public ResponseEntity<Void> deactivateUser(
            @Parameter(description = "Email of the account", required = true)
            @RequestParam("email") String email)
            throws FatumUserException {
        userService.deactivateUser(email);
        return ResponseEntity.noContent().build();
    }

    /** Gives the access back to an account that was deactivated. */
    @Operation(summary = "Activate an account",
            description = "Gives the access back. The event carries the role, because the consumer has "
                    + "to restore the groups of the pool as well, and a professional keeps its own.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The account was activated"),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that email",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/activate")
    public ResponseEntity<Void> activateUser(
            @Parameter(description = "Email of the account", required = true)
            @RequestParam("email") String email)
            throws FatumUserException {
        userService.activateUser(email);
        return ResponseEntity.noContent().build();
    }
}
