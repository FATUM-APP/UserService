package fatum.controller;

import fatum.dto.UserResponse;
import fatum.exception.FatumUserException;
import fatum.service.UserService;
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
@RestController
@RequestMapping("/admin/users")
@Validated
@PreAuthorize("hasRole('ADMIN')")
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
    @GetMapping
    public ResponseEntity<UserResponse> getUserByEmail(@RequestParam("email") String email)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponse(userService.getUserByEmail(email)));
    }

    /** Searches by name over every account, active or not. */
    @GetMapping("/search")
    public ResponseEntity<List<UserResponse>> getUsersByName(@RequestParam("name") String name)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toResponseList(userService.getUsersByName(name)));
    }

    /** Takes away the access of an account and every session it had open. */
    @PutMapping("/deactivate")
    public ResponseEntity<Void> deactivateUser(@RequestParam("email") String email)
            throws FatumUserException {
        userService.deactivateUser(email);
        return ResponseEntity.noContent().build();
    }

    /** Gives the access back to an account that was deactivated. */
    @PutMapping("/activate")
    public ResponseEntity<Void> activateUser(@RequestParam("email") String email)
            throws FatumUserException {
        userService.activateUser(email);
        return ResponseEntity.noContent().build();
    }
}