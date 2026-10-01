package fatum.verification.analyzer;

/**
 * Proof of life with Amazon Rekognition Face Liveness.
 *
 * <p>The service never sees the video: the client streams it straight into Rekognition with temporary
 * credentials. What the service does is open the session, which is also the point where the paid
 * check is spent, and then ask for the verdict.</p>
 */
public interface FaceLivenessClient {

    /**
     * Opens a session for the user.
     *
     * @return the session, or {@code null} when the proof of life is not configured or Rekognition is
     *         unreachable; the caller turns that into a service error instead of a rejection
     */
    LivenessSession createSession(String userAwsId);

    /**
     * Asks Rekognition for the verdict of a session.
     *
     * @return never null: an unreachable service is reported as
     *         {@link LivenessStatus#UNAVAILABLE}, which is not a decision about the person
     */
    LivenessResult result(String sessionId);
}