package fatum.model;

import fatum.model.constant.VerificationBand;
import fatum.model.constant.VerificationDecision;
import fatum.model.constant.VerificationOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One run of the identity verification pipeline.
 *
 * <p>It keeps the full picture of the decision: the scoring band, the final outcome, who decided
 * (system or administrator), every partial score, the evidence that was used and the human notes. The
 * attempt history is what lets the policy decide between retrying, rejecting or escalating.</p>
 */
@Entity
@Table(name = "VERIFICATION_ATTEMPTS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class VerificationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Column(name = "ID", length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "USER_AWS_ID", nullable = false)
    private User user;

    @Column(name = "ATTEMPT_NUMBER", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "BAND", nullable = false, length = 20)
    private VerificationBand band;

    @Enumerated(EnumType.STRING)
    @Column(name = "OUTCOME", nullable = false, length = 20)
    private VerificationOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "DECISION", nullable = false, length = 20)
    private VerificationDecision decision;

    @Column(name = "SCORE", nullable = false)
    private double score;

    @Column(name = "DOCUMENT_MATCH")
    private double documentMatch;

    @Column(name = "DOCUMENT_LIVENESS_MATCH")
    private double documentLivenessMatch;

    @Column(name = "PROFILE_LIVENESS_MATCH")
    private double profileLivenessMatch;

    @Column(name = "FRAUD_RISK")
    private double fraudRisk;

    @Column(name = "SUMMARY", length = 1000)
    private String summary;

    @Column(name = "FLAGS", length = 1000)
    private String flags;

    @Column(name = "DOCUMENT_FRONT_KEY", length = 512)
    private String documentFrontKey;

    @Column(name = "DOCUMENT_BACK_KEY", length = 512)
    private String documentBackKey;

    @Column(name = "LIVENESS_KEY", length = 512)
    private String livenessKey;

    @Column(name = "PROFILE_IMAGE_KEY", length = 512)
    private String profileImageKey;

    @Column(name = "DECIDED_BY", length = 255)
    private String decidedBy;

    @Column(name = "NOTES", length = 1000)
    private String notes;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    public VerificationAttempt(
            User user,
            int attemptNumber,
            VerificationBand band,
            VerificationOutcome outcome,
            VerificationDecision decision,
            double score,
            double documentMatch,
            double documentLivenessMatch,
            double profileLivenessMatch,
            double fraudRisk,
            String summary,
            String flags,
            String documentFrontKey,
            String documentBackKey,
            String livenessKey,
            String profileImageKey) {
        this.user = user;
        this.attemptNumber = attemptNumber;
        this.band = band;
        this.outcome = outcome;
        this.decision = decision;
        this.score = clamp(score);
        this.documentMatch = clamp(documentMatch);
        this.documentLivenessMatch = clamp(documentLivenessMatch);
        this.profileLivenessMatch = clamp(profileLivenessMatch);
        this.fraudRisk = clamp(fraudRisk);
        this.summary = truncate(summary);
        this.flags = truncate(flags);
        this.documentFrontKey = documentFrontKey;
        this.documentBackKey = documentBackKey;
        this.livenessKey = livenessKey;
        this.profileImageKey = profileImageKey;
        this.createdAt = Instant.now();
    }

    /** Records the administrator that reviewed the case. */
    public void decidedByAdmin(String adminSubject, String notes) {
        this.decision = VerificationDecision.ADMIN;
        this.decidedBy = adminSubject;
        this.notes = truncate(notes);
    }

    public String flagsAsText() {
        return flags == null ? "" : flags;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
