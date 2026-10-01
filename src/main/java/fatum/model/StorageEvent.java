package fatum.model;

import fatum.model.constant.StorageEventAction;
import fatum.model.constant.StorageEventReason;
import fatum.model.constant.StoredFileType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Audit trail of what the retention policy did with each piece of evidence.
 *
 * <p>It answers "why is this document no longer in the bucket?" with a row such as
 * {@code DOCUMENT_FRONT / DELETED / VERIFIED}, which is exactly the trace the business asked for.</p>
 */
@Entity
@Table(name = "STORAGE_EVENTS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StorageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @Column(name = "ID", length = 36)
    private String id;

    @Column(name = "USER_AWS_ID", nullable = false, length = 255)
    private String userAwsId;

    @Enumerated(EnumType.STRING)
    @Column(name = "FILE_TYPE", nullable = false, length = 30)
    private StoredFileType fileType;

    @Column(name = "OBJECT_KEY", length = 512)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "ACTION", nullable = false, length = 20)
    private StorageEventAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "REASON", nullable = false, length = 40)
    private StorageEventReason reason;

    @Column(name = "DETAIL", length = 500)
    private String detail;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    public StorageEvent(
            String userAwsId,
            StoredFileType fileType,
            String objectKey,
            StorageEventAction action,
            StorageEventReason reason,
            String detail) {
        this.userAwsId = userAwsId;
        this.fileType = fileType;
        this.objectKey = objectKey;
        this.action = action;
        this.reason = reason;
        this.detail = detail;
        this.createdAt = Instant.now();
    }
}
