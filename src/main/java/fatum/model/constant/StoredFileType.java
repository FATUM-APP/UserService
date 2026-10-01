package fatum.model.constant;

/** Kind of evidence a stored object holds. Used by the retention audit trail. */
public enum StoredFileType {

    DOCUMENT_FRONT,
    DOCUMENT_BACK,
    LIVENESS,
    PROFILE_IMAGE
}
