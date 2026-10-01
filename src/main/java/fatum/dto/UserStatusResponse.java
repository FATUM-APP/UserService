package fatum.dto;

import fatum.model.constant.VerificationStatus;

/**
 * Verification state of the authenticated user.
 *
 * <p>Replaces the former {@code isAuthenticated} boolean: the client needs to distinguish "not verified
 * yet" from "waiting for an administrator" to show the right screen.</p>
 */
public record UserStatusResponse(VerificationStatus verificationStatus, boolean isVerified) {
}
