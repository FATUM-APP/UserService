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
 *
 * <p>The report is produced twice for a successful first phase: once to say that the proof of life is
 * required, and once when Rekognition answers. Only the second one can carry
 * {@link VerificationOutcome#VERIFIED}.</p>
 */
public record VerificationReport(
        String userAwsId,
        int attemptNumber,
        int attemptsUsed,
        int attemptsRemaining,
        double score,
        double documentMatch,
        double documentProfileMatch,
        double referenceDocumentMatch,
        Double livenessConfidence,
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

    /** True when the client has to run the proof of life before the case can be decided. */
    public boolean needsLiveness() {
        return outcome == VerificationOutcome.AWAITING_LIVENESS;
    }
}
