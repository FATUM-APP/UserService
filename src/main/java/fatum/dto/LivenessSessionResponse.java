package fatum.dto;

import java.time.Instant;

/**
 * A proof of life the client can start streaming into.
 *
 * <p>The client hands {@code sessionId} to the Face Liveness component and starts the camera. The
 * video never reaches this service: it goes straight to Rekognition with temporary credentials.</p>
 *
 * @param sessionId identifier of the session
 * @param expiresAt moment after which Rekognition refuses the stream
 * @param reused    true when an earlier session was still alive and no new paid check was opened
 */
public record LivenessSessionResponse(
        String sessionId,
        Instant expiresAt,
        boolean reused
) {
}