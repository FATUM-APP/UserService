package fatum.repository;

import fatum.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    User findByAuth0Id(String auth0Id);

    User findByEmail(String email);

    User findByUsername(String username);

    User findByPhoneNumber(String phoneNumber);

    User findByDocument(String document);

    List<User> findByNamesIgnoreCaseAndSurnamesIgnoreCase(String names, String surnames);
}
