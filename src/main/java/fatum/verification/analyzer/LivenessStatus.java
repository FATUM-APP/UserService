package fatum.verification.analyzer;

/** Answer of Rekognition about a proof of life. */
public enum LivenessStatus {

    /** A live person was in front of the camera. */
    SUCCEEDED,

    /** No live person: the stream was a photograph, a screen or an attempt at spoofing. */
    FAILED,

    /** The session timed out before the client finished. */
    EXPIRED,

    /** The client asked too early: the session is still being processed. */
    PENDING,

    /** Rekognition, or the call to it, was not usable. It is not a decision about the person. */
    UNAVAILABLE
}