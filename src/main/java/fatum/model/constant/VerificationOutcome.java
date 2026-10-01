package fatum.model.constant;

/**
 * Decision recorded for a verification attempt once the policy has been applied to the whole
 * history.
 *
 * <p>{@link #PENDING} is the one that is not a user status: it means "manual band, retries still
 * available", so the user stays {@link VerificationStatus#UNVERIFIED}.</p>
 */
public enum VerificationOutcome {

    PENDING,
    VERIFIED,
    MANUAL_REVIEW,
    REJECTED;

    /** Maps the outcome to the status the user ends up with. */
    public VerificationStatus toUserStatus() {
        return switch (this) {
            case VERIFIED -> VerificationStatus.VERIFIED;
            case REJECTED -> VerificationStatus.REJECTED;
            case MANUAL_REVIEW -> VerificationStatus.MANUAL_REVIEW;
            case PENDING -> VerificationStatus.UNVERIFIED;
        };
    }
}
