package fatum.model.constant;

/**
 * Where the trusted picture of an account comes from.
 *
 * <p>It matters for two reasons: only a picture produced by Rekognition can be read directly from S3
 * by the face comparator, and only that one is worth keeping as evidence when a case goes to an
 * administrator.</p>
 */
public enum ReferenceSource {

    /** Written by Rekognition while running the proof of life. */
    REKOGNITION,

    /** Uploaded by an administrator while reviewing a case by hand. */
    ADMIN
}