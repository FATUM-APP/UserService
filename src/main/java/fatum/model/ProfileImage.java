package fatum.model;

import fatum.model.constant.ProfileImageStatus;
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
 * Profile picture of a user.
 *
 * <p>A verified account can hold two rows at the same time: the {@code ACTIVE} picture everybody
 * sees and, while it is being checked, the {@code PENDING} one the user has just uploaded. The
 * pending picture is never served: it either becomes active once the face comparator accepts it, or
 * it is discarded.</p>
 */
@Entity
@Table(name = "PROFILE_IMAGES")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProfileImage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Column(name = "ID", length = 36)
    private String id;

    @Column(name = "IMAGE_KEY", nullable = false, length = 512)
    private String imageKey;

    @Column(name = "ORIGINAL_FILENAME", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "CONTENT_TYPE", nullable = false, length = 100)
    private String contentType;

    @Column(name = "FILE_SIZE", nullable = false)
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 20)
    private ProfileImageStatus status = ProfileImageStatus.ACTIVE;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "USER_AWS_ID", nullable = false)
    private User user;

    public ProfileImage(
            String imageKey,
            String originalFilename,
            String contentType,
            long fileSize,
            User user) {
        this(imageKey, originalFilename, contentType, fileSize, user, ProfileImageStatus.ACTIVE);
    }

    public ProfileImage(
            String imageKey,
            String originalFilename,
            String contentType,
            long fileSize,
            User user,
            ProfileImageStatus status) {
        apply(imageKey, originalFilename, contentType, fileSize);
        this.status = status;
        this.user = user;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Replaces the content of the row, used when a checked picture becomes the visible one. */
    public void replace(
            String imageKey,
            String originalFilename,
            String contentType,
            long fileSize) {
        apply(imageKey, originalFilename, contentType, fileSize);
        this.updatedAt = Instant.now();
    }

    /** Turns a checked picture into the one the rest of the platform sees. */
    public void promote() {
        this.status = ProfileImageStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public boolean isPending() {
        return status == ProfileImageStatus.PENDING;
    }

    public boolean isActive() {
        return status == ProfileImageStatus.ACTIVE;
    }

    private void apply(String imageKey, String originalFilename, String contentType, long fileSize) {
        this.imageKey = requireText(imageKey, "imageKey");
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