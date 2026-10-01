package fatum.dto;

import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;
import fatum.verification.analyzer.LivenessStatus;

import java.util.List;

/**
 * What happened with a proof of life.
 *
 * <p>{@link LivenessStatus#PENDING} is not a failure: Rekognition is still processing the video and
 * the client should ask again in a moment. Every other status closes the case.</p>
 *
 * @param sessionId               session that was reported
 * @param status                  what Rekognition answered
 * @param confidence              confidence of the proof of life, null while it is still open
 * @param identityVerified        true only when the identity ended up verified
 * @param userStatus              resulting status of the user
 * @param outcome                 resulting outcome of the attempt
 * @param referenceDocumentMatch  similarity between the live reference and the document
 * @param flags                   signals collected during the decision
 * @param summary                 human readable summary, kept in the attempt
 */
public record LivenessResultResponse(
        String sessionId,
        LivenessStatus status,
        Double confidence,
        boolean identityVerified,
        VerificationStatus userStatus,
        VerificationOutcome outcome,
        double referenceDocumentMatch,
        List<String> flags,
        String summary
) {

    public LivenessResultResponse {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }
}