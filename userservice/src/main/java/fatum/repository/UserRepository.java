package fatum.repository;

import fatum.model.User;
import fatum.model.constant.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    User findByAwsId(String auth0Id);

    User findByEmailIgnoreCase(String email);

    User findByUsernameIgnoreCase(String username);

    User findByPhoneNumber(String phoneNumber);

    User findByDocumentIgnoreCase(String document);

    List<User> findByNameIgnoreCaseAndIsActive(String name, boolean isActive);

    List<User> findByNameIgnoreCase(String name);

    List<User> findByRoleAndIsActive(UserRole role, boolean isActive);

}
