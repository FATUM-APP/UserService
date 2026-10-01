package fatum.dto;

import fatum.model.constant.VerificationStatus;

/**
 * Current state of the verification process of a user, used by the client to decide which screen to
 * show next (upload the document, upload the liveness, submit, or wait for an administrator).
 */
public record VerificationStatusResponse(
        VerificationStatus status,
        int attemptsUsed,
        int attemptsRemaining,
        boolean canAttempt,
        boolean documentUploaded,
        boolean livenessUploaded,
        boolean profileImageUploaded,
        VerificationAttemptResponse lastAttempt
) {
}
