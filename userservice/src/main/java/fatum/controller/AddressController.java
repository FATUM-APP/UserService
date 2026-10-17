package fatum.controller;

import fatum.dto.AddressDTO;
import fatum.dto.ApiError;
import fatum.dto.NewAddressRequest;
import fatum.dto.mapper.AddressMapper;
import fatum.exception.FatumUserException;
import fatum.model.Address;
import fatum.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The addresses of the authenticated account.
 *
 * <p>Every operation works on the account of the token, so no request carries the identifier of
 * somebody else: the {@code sub} of the JWT is the account. The address is named by its alias, which
 * travels as a query parameter: it is a short name chosen by the account, while a residence like
 * {@code Calle 1 # 2-3} would bring spaces and an almohadilla that do not belong in a path.</p>
 */
@Tag(name = "Addresses", description = "The addresses of the account of the token.")
@RestController
@RequestMapping("/users/addresses")
@Validated
public class AddressController {

    private final UserService userService;

    public AddressController(UserService userService) {
        this.userService = userService;
    }

    /** Every address of the account, the principal one first. */
    @Operation(summary = "List the addresses of the account",
            description = "The order is the contract: the first one is the principal address.")
    @ApiResponse(responseCode = "200", description = "The addresses, in order, possibly none")
    @GetMapping("/mine")
    public ResponseEntity<List<AddressDTO>> listAddresses(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTOList(userService.getAddresses(jwt.getSubject())));
    }

    /** The address that represents the account. */
    @Operation(summary = "Read the principal address",
            description = "The first address of the list, which is the one that represents the account.")
    @ApiResponse(responseCode = "404", description = "The account has no addresses",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/principal")
    public ResponseEntity<AddressDTO> getPrincipalAddress(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTO(userService.getPrincipalAddress(jwt.getSubject())));
    }

    @Operation(summary = "Read one address of the account",
            description = "Looks the address up by its alias inside the account of the token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The address"),
            @ApiResponse(responseCode = "404", description = "The account has no address with that alias",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/select")
    public ResponseEntity<AddressDTO> selectAddress(@AuthenticationPrincipal Jwt jwt,
                                                    @Parameter(description = "Alias that identifies the address", required = true)
                                                    @RequestParam("alias") String alias)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTO(userService.selectAddress(jwt.getSubject(), alias)));
    }

    /** The address that represents the account. */
    /**
     * The address that represents a professional, looked up by the username.
     *
     * <p>It is the only address resolved by username: the caller is another account, and it knows the
     * username it saw in a listing, never the aws identifier.</p>
     */
    @Operation(summary = "Read the address of a professional",
            description = "The only address read by username, because the caller is another account: it "
                    + "knows the username it saw in a listing, never the aws identifier. The professional "
                    + "has to still have access.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The principal address of the professional"),
            @ApiResponse(responseCode = "403", description = "The account was deactivated",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No account with that username",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "The account is not a professional, or has no address",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/professional-address")
    public ResponseEntity<AddressDTO> getProfessionalAddress(
            @Parameter(description = "Username of the professional", required = true)
            @RequestParam("username") String username)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTO(userService.getProfessionalAddress(username)));
    }

    @Operation(summary = "Add an address",
            description = "Adds an address to the account of the token. The alias is unique inside the "
                    + "account, so it is checked before the address is stored. When no alias is sent, the "
                    + "residence is used as one.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The stored address"),
            @ApiResponse(responseCode = "409", description = "The account already uses that alias",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "The address is not acceptable",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/new")
    public ResponseEntity<AddressDTO> addAddress(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody NewAddressRequest request) throws FatumUserException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toDTO(userService.addAddress(jwt.getSubject(), request)));
    }

    /** Moves one address to the principal place. */
    @Operation(summary = "Make an address the principal one",
            description = "Moves the address to the head of the list, which is what makes it principal. "
                    + "Everything the platform reads for this account, including the address of a "
                    + "professional, follows the move.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The address that is principal now"),
            @ApiResponse(responseCode = "404", description = "The account has no address with that alias",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/principal")
    public ResponseEntity<AddressDTO> makePrincipalAddress(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Alias that identifies the address", required = true)
            @RequestParam("alias") String alias) throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTO(userService.makePrincipalAddress(jwt.getSubject(), alias)));
    }

    @Operation(summary = "Delete an address",
            description = "Deletes the address by its alias. A professional cannot be left without "
                    + "addresses: that would remove the place it offers its service from.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The address was deleted"),
            @ApiResponse(responseCode = "404", description = "The account has no address with that alias",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "It is the last address of a professional",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/delete")
    public ResponseEntity<Void> removeAddress(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Alias that identifies the address", required = true)
            @RequestParam("alias") String alias) throws FatumUserException {
        userService.removeAddress(jwt.getSubject(), alias);
        return ResponseEntity.noContent().build();
    }

    private List<AddressDTO> toDTOList(List<Address> addresses) {
        String owner = addresses.isEmpty() ? null : addresses.getFirst().getUser().getName();
        return AddressMapper.toDTOList(addresses, owner);
    }

    private AddressDTO toDTO(Address address) {
        return AddressMapper.toDTO(address, address.getUser().getName());
    }

}
