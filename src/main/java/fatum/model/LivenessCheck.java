package fatum.model;

import fatum.model.constant.LivenessCheckStatus;
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
 * One proof of life run against Amazon Rekognition Face Liveness.
 *
 * <p>The row is created before the client opens the camera, because the session identifier has to
 * exist for the client to start streaming, and it is the only thing that links a session to its
 * owner: without it anyone could complete somebody else's session.</p>
 *
 * <p>The reference picture Rekognition writes stays in S3: the row keeps the bucket and the key, not
 * the bytes.</p>
 */
@Entity
@Table(name = "LIVENESS_CHECKS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LivenessCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Column(name = "ID", length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "USER_AWS_ID", nullable = false)
    private User user;

    /**
     * The attempt that requested the proof. It is mandatory on purpose: a session can only exist
     * while a cheap first phase is waiting for it, which is what keeps the paid checks from being
     * requested at will.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ATTEMPT_ID", nullable = false)
    private VerificationAttempt attempt;

    @Column(name = "SESSION_ID", nullable = false, unique = true, length = 128)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 20)
    private LivenessCheckStatus status = LivenessCheckStatus.CREATED;

    /** Confidence reported by Rekognition, 0 to 100. Null while the check is open. */
    @Column(name = "CONFIDENCE")
    private Double confidence;

    @Column(name = "REFERENCE_BUCKET", length = 255)
    private String referenceBucket;

    @Column(name = "REFERENCE_KEY", length = 512)
    private String referenceKey;

    @Column(name = "FAILURE_REASON", length = 500)
    private String failureReason;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "COMPLETED_AT")
    private Instant completedAt;

    public LivenessCheck(User user, VerificationAttempt attempt, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        this.user = user;
        this.attempt = attempt;
        this.sessionId = sessionId.trim();
        this.createdAt = Instant.now();
    }

    /** Rekognition accepted the proof of life and produced the reference picture. */
    public void succeed(double confidence, String referenceBucket, String referenceKey) {
        this.status = LivenessCheckStatus.SUCCEEDED;
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.referenceBucket = referenceBucket;
        this.referenceKey = referenceKey;
        this.failureReason = null;
        this.completedAt = Instant.now();
    }

    /** Rekognition rejected the proof of life, or could not produce a reference picture. */
    public void fail(String reason) {
        this.status = LivenessCheckStatus.FAILED;
        this.failureReason = truncate(reason);
        this.completedAt = Instant.now();
    }

    /** The client never finished; the session expired inside Rekognition. */
    public void expire(String reason) {
        this.status = LivenessCheckStatus.EXPIRED;
        this.failureReason = truncate(reason);
        this.completedAt = Instant.now();
    }

    public boolean hasSucceeded() {
        return status == LivenessCheckStatus.SUCCEEDED;
    }

    public boolean isOpen() {
        return status == LivenessCheckStatus.CREATED;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}