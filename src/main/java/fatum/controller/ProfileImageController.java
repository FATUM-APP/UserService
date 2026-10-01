package fatum.controller;

import fatum.dto.ProfileImageResponse;
import fatum.exception.FatumUserException;
import fatum.service.ProfileImageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Profile picture of the authenticated user.
 *
 * <p>For a verified account the change is not immediate: the picture is queued and compared with the
 * live reference, so the upload answers {@code pendingVerification = true} and this same resource is
 * polled until the answer arrives. The picture being checked is never served to anybody.</p>
 */
@RestController
@RequestMapping("/profile-image")
public class ProfileImageController {

    private final ProfileImageService profileImageService;

    public ProfileImageController(ProfileImageService profileImageService) {
        this.profileImageService = profileImageService;
    }

    @PutMapping(path = "/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProfileImageResponse> replace(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("image") MultipartFile image) throws FatumUserException {
        return ResponseEntity.ok(profileImageService.replace(jwt.getSubject(), image));
    }

    @GetMapping({"", "/"})
    public ResponseEntity<ProfileImageResponse> get(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.ok(profileImageService.get(jwt.getSubject()));
    }
}