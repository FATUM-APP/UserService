package fatum.repository;

import fatum.model.VerificationAttempt;
import fatum.model.constant.VerificationOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface VerificationAttemptRepository extends JpaRepository<VerificationAttempt, String> {

    List<VerificationAttempt> findByUserAwsIdOrderByAttemptNumberAsc(String awsId);

    List<VerificationAttempt> findByUserAwsIdOrderByAttemptNumberDesc(String awsId);

    long countByUserAwsId(String awsId);

    /** Attempts waiting for an administrator, newest first. */
    List<VerificationAttempt> findByOutcomeInOrderByCreatedAtDesc(Collection<VerificationOutcome> outcomes);
}
