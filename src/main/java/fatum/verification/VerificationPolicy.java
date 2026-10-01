package fatum.verification;

import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationOutcome;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The rules that turn scores into decisions. It is a pure class on purpose: the whole business policy
 * can be read, tested and changed in one place.
 *
 * <p>Scoring bands (configurable):</p>
 * <pre>
 *   score &gt;= verifiedThreshold (80)          -&gt; VERIFIED
 *   manualThreshold (30) .. verifiedThreshold -&gt; MANUAL
 *   score &lt; manualThreshold                   -&gt; REJECTED
 *   forged document or document that does not match the user -&gt; REJECTED
 * </pre>
 *
 * <p>Decision over the attempt history, applied in this order:</p>
 * <ol>
 *   <li>Any attempt in the VERIFIED band verifies the user.</li>
 *   <li>Two consecutive attempts below {@code hardRejectThreshold} (20) reject the user outright:
 *       "in the following two attempts, below 20% means rejected immediately".</li>
 *   <li>Mixed evidence (at least one MANUAL and at least one REJECTED) escalates to MANUAL_REVIEW:
 *       "if one was manual and another rejected, it stays in manual review".</li>
 *   <li>A rejection that is not mixed rejects the user; the first attempt is enough, because retries
 *       are only granted after a manual result.</li>
 *   <li>The attempts ran out with manual results: MANUAL_REVIEW.</li>
 *   <li>Anything else keeps the user UNVERIFIED with retries left.</li>
 * </ol>
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
     * Applies the rules to the whole history, including the attempt that has just been scored.
     *
     * @param history every attempt of the user, oldest first, the new one included
     */
    public VerificationOutcome decide(List<ScoredAttempt> history) {
        if (history == null || history.isEmpty()) {
            return VerificationOutcome.PENDING;
        }
        if (history.stream().anyMatch(attempt -> attempt.band() == VerificationBand.VERIFIED)) {
            return VerificationOutcome.VERIFIED;
        }
        if (hasConsecutiveHardRejects(history)) {
            return VerificationOutcome.REJECTED;
        }
        boolean anyManual = history.stream().anyMatch(attempt -> attempt.band() == VerificationBand.MANUAL);
        boolean anyRejected = history.stream().anyMatch(attempt -> attempt.band() == VerificationBand.REJECTED);
        if (anyManual && anyRejected) {
            return VerificationOutcome.MANUAL_REVIEW;
        }
        if (last(history).band() == VerificationBand.REJECTED) {
            return VerificationOutcome.REJECTED;
        }
        if (history.size() >= properties.getMaxAttempts()) {
            return VerificationOutcome.MANUAL_REVIEW;
        }
        return VerificationOutcome.PENDING;
    }

    /** True when the user cannot start another attempt. */
    public boolean canStartAttempt(List<ScoredAttempt> history) {
        if (history == null || history.isEmpty()) {
            return true;
        }
        return history.size() < properties.getMaxAttempts() && decide(history) == VerificationOutcome.PENDING;
    }

    /** Attempts the user has left, used by the status endpoint. */
    public int remainingAttempts(int usedAttempts) {
        return Math.max(0, properties.getMaxAttempts() - usedAttempts);
    }

    /** Maps the persisted attempts to the minimal view the policy needs. */
    public List<ScoredAttempt> toHistory(List<VerificationAttempt> attempts) {
        return attempts.stream()
                .map(attempt -> new ScoredAttempt(attempt.getBand(), attempt.getScore()))
                .toList();
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

    private ScoredAttempt last(List<ScoredAttempt> history) {
        return history.get(history.size() - 1);
    }
}
