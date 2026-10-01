package fatum.verification.analyzer;

import java.util.List;

/**
 * Verdict of the pattern analysis performed with Amazon Bedrock.
 *
 * @param evaluated  false when Bedrock is disabled or failed; the risk is then ignored instead of
 *                   being treated as zero risk
 * @param risk       0 (clean) to 100 (very likely forged)
 * @param flags      short reasons, for example {@code altered-document-number}
 * @param reasoning  explanation produced by the model, kept in the attempt for the administrator
 */
public record FraudAssessment(boolean evaluated, double risk, List<String> flags, String reasoning) {

    public FraudAssessment {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    public static FraudAssessment notEvaluated(String detail) {
        return new FraudAssessment(false, 0d, List.of(), detail);
    }

    public static FraudAssessment of(double risk, List<String> flags, String reasoning) {
        return new FraudAssessment(true, risk, flags, reasoning);
    }
}
