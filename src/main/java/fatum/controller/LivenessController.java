package fatum.controller;

import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.service.LivenessService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Liveness evidence of the authenticated user: the frame that proves a live person is behind the
 * account and that is later compared with the document and with the profile picture.
 */
@RestController
@RequestMapping("/liveness")
public class LivenessController {

    private final LivenessService livenessService;

    public LivenessController(LivenessService livenessService) {
        this.livenessService = livenessService;
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFileResponse> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("image") MultipartFile image) throws FatumUserException {
        return ResponseEntity.ok(livenessService.upload(jwt.getSubject(), image));
    }

    @GetMapping
    public ResponseEntity<StoredFileResponse> get(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.ok(livenessService.get(jwt.getSubject()));
    }
}
