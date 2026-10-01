package fatum.repository;

import fatum.model.ProfileImage;
import fatum.model.constant.ProfileImageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

/**
 * Profile pictures of a user.
 *
 * <p>A verified account can hold two rows at once, so every lookup names the status it wants; the
 * partial unique index on the table guarantees there is at most one of each.</p>
 */
@Repository
public interface ProfileImageRepository extends JpaRepository<ProfileImage, String> {

    Optional<ProfileImage> findByUserAwsIdAndStatus(String awsId, ProfileImageStatus status);

    List<ProfileImage> findAllByUserAwsId(String awsId);

    default Optional<ProfileImage> findActive(String awsId) {
        return findByUserAwsIdAndStatus(awsId, ProfileImageStatus.ACTIVE);
    }

    default Optional<ProfileImage> findPending(String awsId) {
        return findByUserAwsIdAndStatus(awsId, ProfileImageStatus.PENDING);
    }
}
