package fatum.dto;

import fatum.model.constant.VerificationStatus;

/**
 * Verification state of the authenticated user.
 *
 * <p>Clients get both the state and a boolean, so an application can tell "not verified yet" from
 * "waiting for an administrator" without hard-coding the enum values.</p>
 */
public record UserStatusResponse(VerificationStatus verificationStatus, boolean isVerified) {
}