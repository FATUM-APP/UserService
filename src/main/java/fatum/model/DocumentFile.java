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
 * Identity document of a user, stored as photographs.
 *
 * <p>Single sided documents (passport) only fill the front side; the rest need both sides. The object
 * keys point to files held by the shared storage service, so this table never knows a bucket name.</p>
 */
@Entity
@Table(name = "DOCUMENT_FILES")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DocumentFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Column(name = "ID", length = 36)
    private String id;

    @Column(name = "FRONT_KEY", nullable = false, length = 512)
    private String frontKey;

    @Column(name = "BACK_KEY", length = 512)
    private String backKey;

    @Column(name = "FRONT_FILENAME", nullable = false, length = 255)
    private String frontFilename;

    @Column(name = "BACK_FILENAME", length = 255)
    private String backFilename;

    @Column(name = "CONTENT_TYPE", nullable = false, length = 100)
    private String contentType;

    @Column(name = "FRONT_SIZE", nullable = false)
    private long frontSize;

    @Column(name = "BACK_SIZE")
    private Long backSize;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "USER_AWS_ID", nullable = false, unique = true)
    private User user;

    public DocumentFile(
            String frontKey,
            String backKey,
            String frontFilename,
            String backFilename,
            String contentType,
            long frontSize,
            Long backSize,
            User user) {
        apply(frontKey, backKey, frontFilename, backFilename, contentType, frontSize, backSize);
        this.user = user;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Replaces both sides with a freshly uploaded document. */
    public void replace(
            String frontKey,
            String backKey,
            String frontFilename,
            String backFilename,
            String contentType,
            long frontSize,
            Long backSize) {
        apply(frontKey, backKey, frontFilename, backFilename, contentType, frontSize, backSize);
        this.updatedAt = Instant.now();
    }

    public boolean hasBackSide() {
        return backKey != null && !backKey.isBlank();
    }

    private void apply(
            String frontKey,
            String backKey,
            String frontFilename,
            String backFilename,
            String contentType,
            long frontSize,
            Long backSize) {
        this.frontKey = requireText(frontKey, "frontKey");
        this.backKey = backKey == null || backKey.isBlank() ? null : backKey.trim();
        this.frontFilename = requireText(frontFilename, "frontFilename");
        this.backFilename = backFilename == null || backFilename.isBlank() ? null : backFilename.trim();
        this.contentType = requireText(contentType, "contentType");
        this.frontSize = requireNonNegative(frontSize);
        this.backSize = backSize == null ? null : requireNonNegative(backSize);
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
