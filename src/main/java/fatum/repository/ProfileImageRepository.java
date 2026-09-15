package fatum.repository;

import fatum.model.ProfileImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProfileImageRepository extends JpaRepository<ProfileImage, String> {

    Optional<ProfileImage> findByUserAuth0Id(String auth0Id);
}
