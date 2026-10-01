package fatum.model.constant;

/**
 * Life cycle of a Rekognition Face Liveness session.
 *
 * <p>The check is created before the client opens the camera, because the session identifier has to
 * exist for the client to start streaming. It is only when the client reports back that the real
 * verdict is fetched from Rekognition.</p>
 */
public enum LivenessCheckStatus {

    /** The session was created and the client has not reported back yet. */
    CREATED,

    /** Rekognition accepted the proof of life. */
    SUCCEEDED,

    /** Rekognition rejected the proof of life: no live person, or a spoofing attempt. */
    FAILED,

    /** The session timed out before the client finished. */
    EXPIRED
}