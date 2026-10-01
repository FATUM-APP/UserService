package fatum.dto;

import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationAttemptType;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationDecision;
import fatum.model.constant.VerificationOutcome;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * One verification attempt as seen by the client.
 *
 * <p>The evidence keys are deliberately not exposed: the client receives a summary and the scores, and
 * asks for a download URL when it really needs the picture.</p>
 *
 * @param type                   identity verification or profile picture check
 * @param documentMatch          how much of the document agrees with the registered data
 * @param documentProfileMatch   whether the document picture and the profile picture are the same person
 * @param referenceDocumentMatch whether the live reference and the document are the same person
 * @param livenessConfidence     confidence of the proof of life, null when there was none
 */
public record VerificationAttemptResponse(
        String id,
        VerificationAttemptType type,
        int attemptNumber,
        VerificationBand band,
        VerificationOutcome outcome,
        VerificationDecision decision,
        double score,
        double documentMatch,
        double documentProfileMatch,
        double referenceDocumentMatch,
        Double livenessConfidence,
        double fraudRisk,
        String summary,
        List<String> flags,
        String decidedBy,
        String notes,
        Instant createdAt
) {

    public static VerificationAttemptResponse from(VerificationAttempt attempt) {
        if (attempt == null) {
            return null;
        }
        return new VerificationAttemptResponse(
                attempt.getId(),
                attempt.getType(),
                attempt.getAttemptNumber(),
                attempt.getBand(),
                attempt.getOutcome(),
                attempt.getDecision(),
                attempt.getScore(),
                attempt.getDocumentMatch(),
                attempt.getDocumentProfileMatch(),
                attempt.getReferenceDocumentMatch(),
                attempt.getLivenessConfidence(),
                attempt.getFraudRisk(),
                attempt.getSummary(),
                splitFlags(attempt.getFlags()),
                attempt.getDecidedBy(),
                attempt.getNotes(),
                attempt.getCreatedAt());
    }

    private static List<String> splitFlags(String flags) {
        if (flags == null || flags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(flags.split(","))
                .map(String::trim)
                .filter(flag -> !flag.isEmpty())
                .toList();
    }
}
