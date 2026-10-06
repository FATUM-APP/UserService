package fatum.controller;

import fatum.dto.StoredFileResponse;
import fatum.dto.ApiError;
import fatum.exception.FatumUserException;
import fatum.service.ProfileImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The profile picture of the account of the token.
 *
 * <p>There is one picture per account and it can be replaced as many times as the account wants: the
 * previous object is removed from the storage service and the new one takes its place, so nothing is
 * left behind.</p>
 */
@Tag(name = "Profile picture", description = "The profile picture of the account of the token.")
@RestController
@RequestMapping("/profile-image")
public class ProfileImageController {

    private final ProfileImageService profileImageService;

    public ProfileImageController(ProfileImageService profileImageService) {
        this.profileImageService = profileImageService;
    }


    @Operation(summary = "Replace the profile picture",
            description = "Stores the new picture and deletes the previous one. The file goes to the "
                    + "storage service, which decides the bucket: this service only names the route.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The stored picture"),
            @ApiResponse(responseCode = "422", description = "The file is empty or of a type that is not accepted",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "The storage service refused the file",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping(path = "/update",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFileResponse> replace(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("image") MultipartFile image)
            throws FatumUserException {
        return ResponseEntity.ok(profileImageService.replace(jwt.getSubject(), image));
    }

    @Operation(summary = "Read the profile picture",
            operationId = "getProfileImage",
            description = "The picture of the account of the token.")
    @ApiResponse(responseCode = "404", description = "The account has no picture",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping({"", "/"})
    public ResponseEntity<StoredFileResponse> get(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.ok(profileImageService.get(jwt.getSubject()));
    }
}
