package fatum.verification.analyzer;

/**
 * Verdict Rekognition gives for a proof of life.
 *
 * <p>The distinction between {@link LivenessStatus#FAILED} and
 * {@link LivenessStatus#UNAVAILABLE} is the whole point of this type: a person who fails the proof
 * of life is a decision, and an AWS outage is not. Only the first one closes the case.</p>
 *
 * @param status        what Rekognition answered
 * @param confidence    0 to 100, only meaningful when the proof succeeded
 * @param referenceBucket bucket of the picture Rekognition produced
 * @param referenceKey  key of that picture
 * @param detail        short reason, kept in the attempt for the administrator
 */
public record LivenessResult(
        LivenessStatus status,
        double confidence,
        String referenceBucket,
        String referenceKey,
        String detail
) {

    public static LivenessResult unavailable(String detail) {
        return new LivenessResult(LivenessStatus.UNAVAILABLE, 0d, null, null, detail);
    }

    public static LivenessResult pending(String detail) {
        return new LivenessResult(LivenessStatus.PENDING, 0d, null, null, detail);
    }

    public boolean succeeded() {
        return status == LivenessStatus.SUCCEEDED;
    }

    /** True when the answer closes the case: the person either proved to be alive or did not. */
    public boolean isDecision() {
        return status == LivenessStatus.SUCCEEDED
                || status == LivenessStatus.FAILED
                || status == LivenessStatus.EXPIRED;
    }

    public boolean hasReferenceImage() {
        return referenceBucket != null && !referenceBucket.isBlank()
                && referenceKey != null && !referenceKey.isBlank();
    }
}