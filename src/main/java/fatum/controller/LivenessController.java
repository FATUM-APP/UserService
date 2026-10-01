package fatum.controller;

import fatum.dto.LivenessResultResponse;
import fatum.dto.LivenessSessionResponse;
import fatum.dto.StoredFileResponse;
import fatum.exception.FatumUserException;
import fatum.service.LivenessService;
import fatum.verification.LivenessSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proof of life of the authenticated user, run by Amazon Rekognition Face Liveness.
 *
 * <p>The client asks for a session, streams the camera into Rekognition with it, and then reports the
 * session here. There is no endpoint to upload a picture: a photograph taken beforehand proves nothing
 * about who is behind the screen, so the reference picture of an account can only be produced by
 * Rekognition or, when a human reviews a case, by an administrator.</p>
 */
@RestController
@RequestMapping("/liveness")
public class LivenessController {

    private final LivenessSessionService livenessSessionService;
    private final LivenessService livenessService;

    public LivenessController(LivenessSessionService livenessSessionService, LivenessService livenessService) {
        this.livenessSessionService = livenessSessionService;
        this.livenessService = livenessService;
    }

    /**
     * Opens a proof of life, or hands back one that is still alive.
     *
     * <p>It answers 409 when the case is not waiting for one, which is what stops the paid checks from
     * being requested at will.</p>
     */
    @PostMapping("/sessions")
    public ResponseEntity<LivenessSessionResponse> start(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.ok(livenessSessionService.start(jwt.getSubject()));
    }

    /**
     * Reports a session and closes the attempt with the verdict of Rekognition.
     *
     * <p>It is idempotent. A session that is still being processed answers
     * {@code PENDING} and the client asks again in a moment.</p>
     */
    @PostMapping("/sessions/{sessionId}/complete")
    public ResponseEntity<LivenessResultResponse> complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String sessionId) throws FatumUserException {
        return ResponseEntity.ok(livenessSessionService.complete(jwt.getSubject(), sessionId));
    }

    /** The trusted picture of the account, once it exists. */
    @GetMapping
    public ResponseEntity<StoredFileResponse> get(
            @AuthenticationPrincipal Jwt jwt) throws FatumUserException {
        return ResponseEntity.ok(livenessService.get(jwt.getSubject()));
    }
}