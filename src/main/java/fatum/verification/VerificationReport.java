package fatum.verification;

import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationOutcome;
import fatum.model.constant.VerificationStatus;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of one verification attempt, as returned to the client and stored in the attempt row.
 *
 * <p>Every partial score is exposed on purpose: a user who scores 45% deserves to know whether the
 * document did not match, whether the face did not match or whether the picture was simply unreadable.</p>
 */
public record VerificationReport(
        String userAwsId,
        int attemptNumber,
        int attemptsUsed,
        int attemptsRemaining,
        double score,
        double documentMatch,
        double documentLivenessMatch,
        double profileLivenessMatch,
        double fraudRisk,
        VerificationBand band,
        VerificationOutcome outcome,
        VerificationStatus userStatus,
        List<String> flags,
        String summary,
        Instant decidedAt
) {

    public VerificationReport {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }
}
