package fatum.repository;

import fatum.model.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AddressRepository extends JpaRepository<Address, String> {

    // 1. Obtener la dirección si existe. Devuelve una sola fila: el par (aws_id, alias) es único.
    Address findByUserAwsIdAndAlias(String awsId, String alias);

    // 2. Comprobar existencia rápida (SELECT 1) antes de guardar
    boolean existsByUserAwsIdAndAlias(String awsId, String alias);
}