package fatum.verification.analyzer;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Combines the individual signals into the 0-100 score the policy consumes.
 *
 * <p>Weights are re-normalised over the signals that were actually evaluated. That way an environment
 * with Bedrock switched off is not systematically harsher than one with it on, and a document whose
 * fields could not be read does not drag the score to zero by itself.</p>
 */
@Component
public class VerificationScoreCalculator {

    public double composite(List<ScoreComponent> components) {
        if (components == null || components.isEmpty()) {
            return 0d;
        }
        double totalWeight = components.stream()
                .filter(ScoreComponent::evaluated)
                .mapToDouble(ScoreComponent::weight)
                .sum();
        if (totalWeight <= 0d) {
            return 0d;
        }
        double weighted = components.stream()
                .filter(ScoreComponent::evaluated)
                .mapToDouble(component -> component.score() * component.weight())
                .sum();
        return clamp(weighted / totalWeight);
    }

    public double clamp(double value) {
        return Math.max(0d, Math.min(100d, value));
    }
}
