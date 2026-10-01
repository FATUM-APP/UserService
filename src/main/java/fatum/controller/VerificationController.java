package fatum.controller;

import fatum.dto.VerificationAttemptResponse;
import fatum.dto.VerificationStatusResponse;
import fatum.exception.FatumUserException;
import fatum.verification.VerificationReport;
import fatum.verification.VerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Identity verification of the authenticated user.
 *
 * <p>The flow the client follows is: upload the identity document, upload the liveness frame, make sure a
 * profile picture exists, then submit. The status endpoint tells the client which of those steps is still
 * missing and how many attempts are left.</p>
 */
@RestController
@RequestMapping("/verification")
public class VerificationController {

    private final VerificationService verificationService;

    public VerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    /** Runs one attempt with the evidence already uploaded. */
    @PostMapping("/submit")
    public ResponseEntity<VerificationReport> submit(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.ok(verificationService.submit(jwt.getSubject()));
    }

    @GetMapping("/status")
    public ResponseEntity<VerificationStatusResponse> status(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        VerificationService.VerificationState state = verificationService.state(jwt.getSubject());
        return ResponseEntity.ok(new VerificationStatusResponse(
                state.status(),
                state.attemptsUsed(),
                state.attemptsRemaining(),
                state.canAttempt(),
                state.documentUploaded(),
                state.livenessUploaded(),
                state.profileImageUploaded(),
                VerificationAttemptResponse.from(state.lastAttempt())));
    }

    /** History of the attempts of the user, newest first. */
    @GetMapping("/history")
    public ResponseEntity<List<VerificationAttemptResponse>> history(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.ok(verificationService.history(jwt.getSubject()).stream()
                .map(VerificationAttemptResponse::from)
                .toList());
    }
}
