package fatum.model.constant;

/**
 * Why an object was deleted or kept.
 *
 * <p>The audit trail answers the question "why is this document gone?" long after the attempt row is
 * the only remaining evidence.</p>
 */
public enum StorageEventReason {

    /** Identity confirmed: the identity document is deleted, the profile picture and liveness stay. */
    VERIFIED,
    /** Manual band with retries left: every piece of evidence is wiped so the user starts over. */
    MANUAL_RETRY_RESET,
    /** The case goes to an administrator: the document is kept, the liveness and picture are wiped. */
    ADMIN_REVIEW_REQUIRED,
    /** An administrator confirmed the identity: the evidence is kept for the record. */
    ADMIN_VERIFIED,
    /** The user replaced the object with a newer one. */
    REPLACED
}
