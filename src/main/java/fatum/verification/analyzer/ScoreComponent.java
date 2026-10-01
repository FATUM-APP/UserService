package fatum.verification.analyzer;

/**
 * One signal of the verification score.
 *
 * @param name      signal name, used in the summary
 * @param score     0 to 100
 * @param weight    relative importance
 * @param evaluated false when the signal could not be computed, in which case it is excluded and the
 *                  remaining weights are re-normalised
 */
public record ScoreComponent(String name, double score, double weight, boolean evaluated) {

    public static ScoreComponent of(String name, double score, double weight) {
        return new ScoreComponent(name, score, weight, true);
    }

    public static ScoreComponent notEvaluated(String name, double weight) {
        return new ScoreComponent(name, 0d, weight, false);
    }
}
