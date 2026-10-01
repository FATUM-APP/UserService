package fatum.model.constant;

/**
 * Decision recorded for a verification attempt once the policy has been applied to the whole
 * history.
 *
 * <p>{@link #PENDING} is the one that is not a user status: it means "manual band, retries still
 * available", so the user stays {@link VerificationStatus#UNVERIFIED}.</p>
 *
 * <p>{@link #AWAITING_LIVENESS} is the intermediate state of the cheap first phase: the document and
 * the picture already match, so the expensive liveness proof is requested. The attempt stays open
 * until Rekognition answers, and it is then rewritten as {@link #VERIFIED} or
 * {@link #MANUAL_REVIEW}.</p>
 */
public enum VerificationOutcome {

    PENDING,
    AWAITING_LIVENESS,
    VERIFIED,
    MANUAL_REVIEW,
    REJECTED;

    /** Maps the outcome to the status the user ends up with. */
    public VerificationStatus toUserStatus() {
        return switch (this) {
            case VERIFIED -> VerificationStatus.VERIFIED;
            case REJECTED -> VerificationStatus.REJECTED;
            case MANUAL_REVIEW -> VerificationStatus.MANUAL_REVIEW;
            case PENDING, AWAITING_LIVENESS -> VerificationStatus.UNVERIFIED;
        };
    }

    /** True while the attempt is still open, waiting for its last piece of evidence. */
    public boolean isOpen() {
        return this == PENDING || this == AWAITING_LIVENESS;
    }
}
