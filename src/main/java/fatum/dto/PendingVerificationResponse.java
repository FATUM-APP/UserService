package fatum.dto;

import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;

import java.time.Instant;

/** A case waiting for an administrator, as shown in the review queue. */
public record PendingVerificationResponse(
        String userAwsId,
        String name,
        String username,
        VerificationStatus status,
        int attemptsUsed,
        VerificationOutcome lastOutcome,
        Double lastScore,
        String lastSummary,
        Instant lastAttemptAt
) {
}
