package fatum.controller;

import fatum.dto.AddressDTO;
import fatum.dto.NewAddressRequest;
import fatum.dto.mapper.AddressMapper;
import fatum.exception.FatumUserException;
import fatum.model.Address;
import fatum.service.UserService;
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
@RestController
@RequestMapping("/users/addresses")
@Validated
public class AddressController {

    private final UserService userService;

    public AddressController(UserService userService) {
        this.userService = userService;
    }

    /** Every address of the account, the principal one first. */
    @GetMapping("/mine")
    public ResponseEntity<List<AddressDTO>> listAddresses(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTOList(userService.getAddresses(jwt.getSubject())));
    }

    /** The address that represents the account. */
    @GetMapping("/principal")
    public ResponseEntity<AddressDTO> getPrincipalAddress(@AuthenticationPrincipal Jwt jwt)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTO(userService.getPrincipalAddress(jwt.getSubject())));
    }

    @GetMapping("/select")
    public ResponseEntity<AddressDTO> selectAddress(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam("alias") String alias)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTO(userService.getPrincipalAddress(jwt.getSubject())));
    }

    /** The address that represents the account. */
    /**
     * The address that represents a professional, looked up by the username.
     *
     * <p>It is the only address resolved by username: the caller is another account, and it knows the
     * username it saw in a listing, never the aws identifier.</p>
     */
    @GetMapping("/professional-address")
    public ResponseEntity<AddressDTO> getProfessionalAddress(@RequestParam("username") String username)
            throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTO(userService.getProfessionalAddress(username)));
    }

    @PostMapping()
    public ResponseEntity<AddressDTO> addAddress(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody NewAddressRequest request) throws FatumUserException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toDTO(userService.addAddress(jwt.getSubject(), request)));
    }

    @PutMapping
    public ResponseEntity<AddressDTO> updateAddress(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("alias") String alias,
            @Valid @RequestBody NewAddressRequest request) throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTO(userService.updateAddress(jwt.getSubject(), alias, request)));
    }

    /** Moves one address to the principal place. */
    @PutMapping("/principal")
    public ResponseEntity<AddressDTO> makePrincipalAddress(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("alias") String alias) throws FatumUserException {
        return ResponseEntity.status(HttpStatus.OK)
                .body(toDTO(userService.makePrincipalAddress(jwt.getSubject(), alias)));
    }

    @DeleteMapping
    public ResponseEntity<Void> removeAddress(
            @AuthenticationPrincipal Jwt jwt,
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
