package fatum.verification.analyzer;

import java.time.Instant;

/**
 * A proof of life that Rekognition has opened and the client can now stream into.
 *
 * @param sessionId identifier the client hands to the liveness component and reports back later
 * @param expiresAt moment after which Rekognition refuses to accept the stream
 * @param referenceBucket bucket where Rekognition writes the reference picture
 * @param referenceKeyPrefix prefix Rekognition writes it under
 */
public record LivenessSession(
        String sessionId,
        Instant expiresAt,
        String referenceBucket,
        String referenceKeyPrefix
) {
}