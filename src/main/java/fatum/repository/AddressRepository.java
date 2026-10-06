package fatum.repository;

import fatum.model.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AddressRepository extends JpaRepository<Address, String> {

    // 1. Obtener la dirección si existe. Devuelve una sola fila: el par (aws_id, alias) es único.
    // 1. Reads the address when it exists. It returns one row: the pair (aws_id, alias) is unique.
    Address findByUserAwsIdAndAlias(String awsId, String alias);

    // 2. Cheap existence check (SELECT 1) before saving.
    boolean existsByUserAwsIdAndAlias(String awsId, String alias);
}
