package fatum.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Liveness evidence of a user: the frame captured by the liveness proof.
 *
 * <p>It is the reference picture every later comparison uses: the identity document is compared
 * against it, and a profile picture change is accepted only when the new picture still matches it.</p>
 */
@Entity
@Table(name = "LIVENESS_FILES")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LivenessFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Column(name = "ID", length = 36)
    private String id;

    @Column(name = "LIVENESS_KEY", nullable = false, length = 512)
    private String livenessKey;

    @Column(name = "ORIGINAL_FILENAME", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "CONTENT_TYPE", nullable = false, length = 100)
    private String contentType;

    @Column(name = "FILE_SIZE", nullable = false)
    private long fileSize;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "USER_AWS_ID", nullable = false, unique = true)
    private User user;

    public LivenessFile(
            String livenessKey,
            String originalFilename,
            String contentType,
            long fileSize,
            User user) {
        apply(livenessKey, originalFilename, contentType, fileSize);
        this.user = user;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void replace(String livenessKey, String originalFilename, String contentType, long fileSize) {
        apply(livenessKey, originalFilename, contentType, fileSize);
        this.updatedAt = Instant.now();
    }

    private void apply(String livenessKey, String originalFilename, String contentType, long fileSize) {
        this.livenessKey = requireText(livenessKey, "livenessKey");
        this.originalFilename = requireText(originalFilename, "originalFilename");
        this.contentType = requireText(contentType, "contentType");
        this.fileSize = requireNonNegative(fileSize);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static long requireNonNegative(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("fileSize cannot be negative");
        }
        return value;
    }
}
