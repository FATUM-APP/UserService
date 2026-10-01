package fatum.repository;

import fatum.model.StorageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StorageEventRepository extends JpaRepository<StorageEvent, String> {

    List<StorageEvent> findByUserAwsIdOrderByCreatedAtDesc(String awsId);
}
