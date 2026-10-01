package fatum.verification.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationScoreCalculatorTest {

    private final VerificationScoreCalculator calculator = new VerificationScoreCalculator();

    @Test
    void combinesEverySignalWithItsWeight() {
        double score = calculator.composite(List.of(
                ScoreComponent.of("documentMatch", 100d, 0.35d),
                ScoreComponent.of("documentLivenessMatch", 80d, 0.30d),
                ScoreComponent.of("profileLivenessMatch", 60d, 0.20d),
                ScoreComponent.of("authenticity", 100d, 0.15d)));

        // 35 + 24 + 12 + 15 = 86
        assertThat(score).isCloseTo(86d, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void renormalizesTheWeightsOverTheSignalsThatWereEvaluated() {
        double score = calculator.composite(List.of(
                ScoreComponent.of("documentMatch", 100d, 0.35d),
                ScoreComponent.notEvaluated("authenticity", 0.15d),
                ScoreComponent.notEvaluated("documentLivenessMatch", 0.30d),
                ScoreComponent.notEvaluated("profileLivenessMatch", 0.20d)));

        assertThat(score).isCloseTo(100d, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void anAnalyzerThatIsSwitchedOffDoesNotDragTheScoreDown() {
        List<ScoreComponent> components = List.of(
                ScoreComponent.of("documentMatch", 90d, 0.35d),
                ScoreComponent.of("documentLivenessMatch", 90d, 0.30d),
                ScoreComponent.of("profileLivenessMatch", 90d, 0.20d),
                ScoreComponent.notEvaluated("authenticity", 0.15d));

        assertThat(calculator.composite(components)).isCloseTo(90d, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void returnsZeroWhenNothingCouldBeEvaluated() {
        assertThat(calculator.composite(List.of(ScoreComponent.notEvaluated("documentMatch", 0.35d)))).isZero();
        assertThat(calculator.composite(List.of())).isZero();
        assertThat(calculator.composite(null)).isZero();
    }

    @Test
    void clampsTheResultToTheZeroHundredRange() {
        assertThat(calculator.clamp(-10)).isZero();
        assertThat(calculator.clamp(140)).isEqualTo(100d);
        assertThat(calculator.clamp(55)).isEqualTo(55d);
    }
}
