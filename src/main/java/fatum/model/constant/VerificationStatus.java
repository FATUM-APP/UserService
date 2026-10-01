package fatum.model.constant;

/**
 * Identity verification state of a user.
 *
 * <p>It replaces the former {@code isAuthenticated} boolean, which could not express "the system
 * could not decide, a human has to look at it".</p>
 *
 * <ul>
 *   <li>{@link #UNVERIFIED}: never verified, or the last attempt was inconclusive and the user still
 *       has retries left.</li>
 *   <li>{@link #VERIFIED}: the system or an administrator confirmed the identity.</li>
 *   <li>{@link #REJECTED}: the evidence contradicts the identity (document does not match, document
 *       looks forged) and no retry is granted.</li>
 *   <li>{@link #MANUAL_REVIEW}: the attempts ran out without a clear answer, or the evidence is
 *       mixed, so an administrator must decide.</li>
 * </ul>
 */
public enum VerificationStatus {

    UNVERIFIED,
    VERIFIED,
    REJECTED,
    MANUAL_REVIEW;

    public boolean isTerminal() {
        return this != UNVERIFIED;
    }
}
