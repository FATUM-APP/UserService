package fatum.verification;

import fatum.model.constant.VerificationBand;

/**
 * Minimal view of an attempt: the band it landed in and the score it obtained. Keeping the policy
 * free of JPA entities makes the rules trivial to test.
 */
public record ScoredAttempt(VerificationBand band, double score) {
}
