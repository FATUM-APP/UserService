package fatum.repository;

import fatum.model.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AddressRepository extends JpaRepository<Address, String> {

    // 1. Obtener la dirección si existe
    Optional<Address> findByUserUsernameAndResidence(String username, String residence);

    // 2. Comprobar existencia rápida (SELECT 1) antes de guardar
    boolean existsByUserUsernameAndResidence(String username, String residence);
}