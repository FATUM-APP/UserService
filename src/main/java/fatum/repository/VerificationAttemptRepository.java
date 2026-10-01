package fatum.repository;

import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationAttemptType;
import fatum.model.constant.VerificationOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Verification attempts.
 *
 * <p>Identity verifications and profile picture checks are counted apart: changing the picture must
 * never consume the attempts a user has left to prove who they are.</p>
 */
@Repository
public interface VerificationAttemptRepository extends JpaRepository<VerificationAttempt, String> {

    List<VerificationAttempt> findByUserAwsIdAndTypeOrderByAttemptNumberAsc(String awsId, VerificationAttemptType type);

    List<VerificationAttempt> findByUserAwsIdAndTypeOrderByAttemptNumberDesc(String awsId, VerificationAttemptType type);

    long countByUserAwsIdAndType(String awsId, VerificationAttemptType type);

    /** Attempts of a kind that are still waiting for something, newest first. */
    Optional<VerificationAttempt> findFirstByUserAwsIdAndTypeAndOutcomeOrderByCreatedAtDesc(
            String awsId,
            VerificationAttemptType type,
            VerificationOutcome outcome);

    /** Used to stop a user from queueing picture changes without limit. */
    long countByUserAwsIdAndTypeAndCreatedAtAfter(String awsId, VerificationAttemptType type, Instant after);

    /** Attempts waiting for an administrator, newest first. */
    List<VerificationAttempt> findByOutcomeInOrderByCreatedAtDesc(Collection<VerificationOutcome> outcomes);
}
