package fatum.verification;

import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationOutcome;
import fatum.verification.analyzer.LivenessStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules that turn scores into decisions. It is a pure class on purpose: the whole business policy
 * can be read, tested and changed in one place.
 *
 * <h2>First phase: the cheap checks</h2>
 * <p>The document is read, its fields are compared with what the user registered and the picture of
 * the document is compared with the profile picture. The proof of life is not requested yet, because
 * it is the only step that costs money per attempt.</p>
 *
 * <pre>
 *   forged document, or document that does not match the profile picture -&gt; REJECTED
 *   score &gt;= verifiedThreshold (80)                                     -&gt; AWAITING_LIVENESS
 *   manualThreshold (30) .. verifiedThreshold                           -&gt; PENDING, retry
 *   score &lt; manualThreshold                                            -&gt; REJECTED / MANUAL_REVIEW
 * </pre>
 *
 * <p>Rules over the history, applied in this order:</p>
 * <ol>
 *   <li>A case an administrator has already closed is never reopened.</li>
 *   <li>A rejection after a manual result escalates to MANUAL_REVIEW: the evidence is mixed.</li>
 *   <li>Two consecutive attempts below {@code hardRejectThreshold} (20) reject the user outright.</li>
 *   <li>The attempts ran out with manual results: MANUAL_REVIEW.</li>
 *   <li>Anything else keeps the user UNVERIFIED with retries left.</li>
 * </ol>
 *
 * <h2>Second phase: the proof of life</h2>
 * <p>It is only reached from {@link VerificationOutcome#AWAITING_LIVENESS}, and it is final in both
 * directions: a person who cannot prove to be alive in front of the camera goes to a human, without
 * another attempt. Everything else already matched, so the only possible explanation left is that
 * somebody is holding the photographs of another person.</p>
 */
@Component
public class VerificationPolicy {

    private final VerificationProperties properties;

    public VerificationPolicy(VerificationProperties properties) {
        this.properties = properties;
    }

    /** Scores a single attempt. */
    public VerificationBand band(double score, boolean forgedOrMismatched) {
        if (forgedOrMismatched) {
            return VerificationBand.REJECTED;
        }
        if (score >= properties.getVerifiedThreshold()) {
            return VerificationBand.VERIFIED;
        }
        if (score >= properties.getManualThreshold()) {
            return VerificationBand.MANUAL;
        }
        return VerificationBand.REJECTED;
    }

    /**
     * Decides the state of the case right after the cheap checks.
     *
     * @param band     band of the attempt that has just been scored
     * @param score    score of that attempt
     * @param previous every earlier attempt of the user, oldest first
     */
    public VerificationOutcome decideAfterFirstPhase(
            VerificationBand band,
            double score,
            List<ScoredAttempt> previous) {
        List<ScoredAttempt> history = previous == null ? new ArrayList<>() : new ArrayList<>(previous);

        VerificationOutcome closed = closingOutcome(history);
        if (closed != null) {
            return closed;
        }
        if (band == VerificationBand.VERIFIED) {
            return VerificationOutcome.AWAITING_LIVENESS;
        }
        history.add(new ScoredAttempt(band, score, VerificationOutcome.PENDING));
        if (hasConsecutiveHardRejects(history)) {
            return VerificationOutcome.REJECTED;
        }
        if (band == VerificationBand.REJECTED) {
            boolean anyManual = previous != null
                    && previous.stream().anyMatch(attempt -> attempt.band() == VerificationBand.MANUAL);
            return anyManual ? VerificationOutcome.MANUAL_REVIEW : VerificationOutcome.REJECTED;
        }
        if (history.size() >= properties.getMaxAttempts()) {
            return VerificationOutcome.MANUAL_REVIEW;
        }
        return VerificationOutcome.PENDING;
    }

    /**
     * Decides the case once Rekognition has answered the proof of life.
     *
     * <p>The reference picture it produced is compared with the document, so passing the proof of life
     * proves that a live person was there and the comparison proves that the person is the owner of the
     * document. A comparison that could not be evaluated is not a pass: it goes to a human.</p>
     *
     * @param answer             verdict of Rekognition
     * @param confidence         confidence of the proof of life, 0 to 100
     * @param referenceEvaluated false when the faces could not be compared at all
     * @param referenceMatch     similarity between the live reference and the document, 0 to 100
     */
    public VerificationOutcome decideLiveness(
            LivenessStatus answer,
            double confidence,
            boolean referenceEvaluated,
            double referenceMatch) {
        if (answer != LivenessStatus.SUCCEEDED) {
            return VerificationOutcome.MANUAL_REVIEW;
        }
        if (confidence < properties.getLiveness().getMinConfidence()) {
            return VerificationOutcome.MANUAL_REVIEW;
        }
        if (!referenceEvaluated || referenceMatch < properties.getFaceSimilarityThreshold()) {
            return VerificationOutcome.MANUAL_REVIEW;
        }
        return VerificationOutcome.VERIFIED;
    }

    /** True when the user cannot start another attempt. */
    public boolean canStartAttempt(List<ScoredAttempt> history) {
        if (history == null || history.isEmpty()) {
            return true;
        }
        ScoredAttempt last = history.get(history.size() - 1);
        if (last.outcome() == VerificationOutcome.AWAITING_LIVENESS) {
            return false;
        }
        return closingOutcome(history) == null && history.size() < properties.getMaxAttempts();
    }

    /** Attempts the user has left, used by the status endpoint. */
    public int remainingAttempts(int usedAttempts) {
        return Math.max(0, properties.getMaxAttempts() - usedAttempts);
    }

    /** Maps the persisted attempts to the minimal view the policy needs. */
    public List<ScoredAttempt> toHistory(List<VerificationAttempt> attempts) {
        return attempts.stream()
                .map(attempt -> new ScoredAttempt(attempt.getBand(), attempt.getScore(), attempt.getOutcome()))
                .toList();
    }

    /** The outcome the case already reached, or null while it is still open. */
    private VerificationOutcome closingOutcome(List<ScoredAttempt> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        VerificationOutcome last = history.get(history.size() - 1).outcome();
        if (last == VerificationOutcome.VERIFIED
                || last == VerificationOutcome.REJECTED
                || last == VerificationOutcome.MANUAL_REVIEW) {
            return last;
        }
        return null;
    }

    private boolean hasConsecutiveHardRejects(List<ScoredAttempt> history) {
        for (int index = 1; index < history.size(); index++) {
            ScoredAttempt previous = history.get(index - 1);
            ScoredAttempt current = history.get(index);
            if (previous.score() < properties.getHardRejectThreshold()
                    && current.score() < properties.getHardRejectThreshold()) {
                return true;
            }
        }
        return false;
    }
}
