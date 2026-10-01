package fatum.dto;

import fatum.model.constant.VerificationStatus;

/**
 * Current state of the verification process of a user, used by the client to decide which screen to
 * show next: upload the document, upload a profile picture, request the proof of life, or wait for an
 * administrator.
 *
 * <p>{@code livenessRequired} is what tells the client to run the Face Liveness component, and it is
 * only ever true after the document and the picture already passed the free checks.</p>
 */
public record VerificationStatusResponse(
        VerificationStatus status,
        int attemptsUsed,
        int attemptsRemaining,
        boolean canAttempt,
        boolean documentUploaded,
        boolean livenessCompleted,
        boolean profileImageUploaded,
        boolean livenessRequired,
        VerificationAttemptResponse lastAttempt
) {
}
