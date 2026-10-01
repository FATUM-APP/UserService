package fatum.model.constant;

/**
 * What a verification attempt is checking.
 *
 * <p>The type exists because the two flows no longer share a budget: an identity verification is
 * limited to a few attempts, while changing the profile picture is a different, independent
 * operation that must not consume them.</p>
 */
public enum VerificationAttemptType {

    /** The identity document, the registered data and the liveness proof. */
    FULL,

    /** Only whether the new profile picture still shows the person of the live reference. */
    FACE_ONLY
}