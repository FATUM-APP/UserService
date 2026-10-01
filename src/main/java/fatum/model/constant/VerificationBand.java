package fatum.model.constant;

/**
 * Result of scoring a single verification attempt, before the attempt policy is applied.
 *
 * <ul>
 *   <li>{@link #VERIFIED}: score at or above the verified threshold.</li>
 *   <li>{@link #MANUAL}: score inside the manual window; the user keeps retrying.</li>
 *   <li>{@link #REJECTED}: score below the manual threshold, or the evidence is clearly forged.</li>
 * </ul>
 */
public enum VerificationBand {

    VERIFIED,
    MANUAL,
    REJECTED
}
