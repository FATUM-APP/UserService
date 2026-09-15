package fatum.repository;

import fatum.model.DocumentFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocumentFileRepository extends JpaRepository<DocumentFile, String> {

    Optional<DocumentFile> findByUserAuth0Id(String auth0Id);
}
