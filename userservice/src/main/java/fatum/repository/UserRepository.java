package fatum.repository;

import fatum.model.User;
import fatum.model.constant.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.addressList WHERE u.awsId = :awsId")
    User findByAwsId(@Param("awsId") String awsId);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.addressList WHERE LOWER(u.email) = LOWER(:email)")
    User findByEmailIgnoreCase(@Param("email") String email);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.addressList WHERE LOWER(u.username) = LOWER(:username)")
    User findByUsernameIgnoreCase(@Param("username") String username);

    User findByPhoneNumber(String phoneNumber);

    User findByDocumentIgnoreCase(String document);

    List<User> findByNameIgnoreCaseAndIsActive(String name, boolean isActive);

    List<User> findByNameIgnoreCase(String name);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.addressList WHERE u.role = :role AND u.isActive = :isActive")
    List<User> findByRoleAndIsActive(@Param("role") UserRole role, @Param("isActive") boolean isActive);

}