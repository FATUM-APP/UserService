package fatum.repository;

import fatum.model.LivenessCheck;
import fatum.model.constant.LivenessCheckStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Proofs of life run against Rekognition.
 *
 * <p>The session identifier is the key lookup: it is what the client reports back, and the row is
 * what proves the session belongs to the user who is completing it.</p>
 */
@Repository
public interface LivenessCheckRepository extends JpaRepository<LivenessCheck, String> {

    Optional<LivenessCheck> findBySessionId(String sessionId);

    List<LivenessCheck> findByUserAwsIdOrderByCreatedAtDesc(String awsId);

    Optional<LivenessCheck> findFirstByUserAwsIdAndStatusOrderByCreatedAtDesc(String awsId, LivenessCheckStatus status);

    /** Open sessions of a user, used to avoid asking Rekognition for a new one while one is alive. */
    List<LivenessCheck> findByUserAwsIdAndStatusOrderByCreatedAtDesc(String awsId, LivenessCheckStatus status);

    /** Sessions already spent by an attempt, used to bound how many times one attempt can retry. */
    long countByAttemptId(String attemptId);
}
