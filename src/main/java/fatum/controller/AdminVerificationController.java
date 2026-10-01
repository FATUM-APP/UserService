package fatum.controller;

import fatum.dto.AdminReviewRequest;
import fatum.dto.PendingVerificationResponse;
import fatum.exception.FatumUserException;
import fatum.verification.AdminVerificationService;
import fatum.verification.VerificationReport;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Manual review of the identities the automatic pipeline could not resolve.
 *
 * <p>Reserved to the {@code ADMIN} group of the user pool. The administrator confirms the identity and
 * uploads the trusted picture, or rejects the case.</p>
 */
@RestController
@RequestMapping("/verification/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminVerificationController {

    private final AdminVerificationService adminVerificationService;

    public AdminVerificationController(AdminVerificationService adminVerificationService) {
        this.adminVerificationService = adminVerificationService;
    }

    /** Cases waiting for a decision. */
    @GetMapping("/pending")
    public ResponseEntity<List<PendingVerificationResponse>> pending() {
        return ResponseEntity.ok(adminVerificationService.pending());
    }

    /**
     * Confirms or rejects the identity of a user.
     *
     * @param photo required when the identity is confirmed: it becomes the profile picture and the
     *              liveness reference of the account
     */
    @PostMapping(path = "/review", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VerificationReport> review(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("userAwsId") String userAwsId,
            @RequestParam("verified") boolean verified,
            @RequestParam(value = "notes", required = false) String notes,
            @RequestPart(value = "photo", required = false) MultipartFile photo) throws FatumUserException {
        return ResponseEntity.ok(adminVerificationService.review(
                jwt.getSubject(),
                new AdminReviewRequest(userAwsId, verified, notes),
                photo));
    }

    /** Same decision as JSON, used when the identity is rejected and no picture is needed. */
    @PostMapping(path = "/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VerificationReport> reviewWithoutPhoto(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody AdminReviewRequest request) throws FatumUserException {
        return ResponseEntity.ok(adminVerificationService.review(jwt.getSubject(), request, null));
    }
}
