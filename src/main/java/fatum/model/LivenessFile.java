package fatum.model;

import fatum.model.constant.ReferenceSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * The trusted picture of a user: the image every later comparison uses.
 *
 * <p>It is produced by Rekognition while running the proof of life, which writes it straight to S3,
 * so the row keeps the bucket and the key instead of the bytes. Only when an administrator adopts a
 * picture by hand does the object live in the ordinary liveness route, and then the bucket is left
 * empty because the storage service already knows where that route points.</p>
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

    /** Zero when the object was written by Rekognition, which does not report a size. */
    @Column(name = "FILE_SIZE", nullable = false)
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "SOURCE", nullable = false, length = 20)
    private ReferenceSource source;

    /** Set only when the object lives in a bucket the storage service does not route. */
    @Column(name = "STORAGE_BUCKET", length = 255)
    private String storageBucket;

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
            ReferenceSource source,
            String storageBucket,
            User user) {
        apply(livenessKey, originalFilename, contentType, fileSize, source, storageBucket);
        this.user = user;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void replace(
            String livenessKey,
            String originalFilename,
            String contentType,
            long fileSize,
            ReferenceSource source,
            String storageBucket) {
        apply(livenessKey, originalFilename, contentType, fileSize, source, storageBucket);
        this.updatedAt = Instant.now();
    }

    /** True when the comparator can read the object straight from S3 instead of downloading it. */
    public boolean hasStorageBucket() {
        return storageBucket != null && !storageBucket.isBlank();
    }

    private void apply(
            String livenessKey,
            String originalFilename,
            String contentType,
            long fileSize,
            ReferenceSource source,
            String storageBucket) {
        this.livenessKey = requireText(livenessKey, "livenessKey");
        this.originalFilename = requireText(originalFilename, "originalFilename");
        this.contentType = requireText(contentType, "contentType");
        this.fileSize = requireNonNegative(fileSize);
        this.source = source == null ? ReferenceSource.ADMIN : source;
        this.storageBucket = storageBucket;
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