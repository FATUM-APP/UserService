package fatum.verification;

import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationOutcome;

/**
 * Minimal view of an attempt: the band it landed in, the score it obtained and the outcome it was
 * given. Keeping the policy free of JPA entities makes the rules trivial to test.
 */
public record ScoredAttempt(VerificationBand band, double score, VerificationOutcome outcome) {
}
