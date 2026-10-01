package fatum.repository;

import fatum.model.LivenessFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LivenessFileRepository extends JpaRepository<LivenessFile, String> {

    Optional<LivenessFile> findByUserAwsId(String awsId);
}
