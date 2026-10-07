package fatum.service;

import fatum.dto.NewAddressRequest;
import fatum.exception.FatumUserException;
import fatum.model.Address;
import fatum.model.User;
import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import fatum.model.constant.UserRole;
import fatum.repository.AddressRepository;
import fatum.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The address CRUD against a real schema.
 *
 * <p>These are not unit tests with mocks on purpose: what can go wrong here does not live in the
 * service. The mapping of the collection, the cascade, the {@code orphanRemoval}, the derived
 * queries and the migrations are what this exercises, so it runs Flyway and Hibernate's
 * {@code validate} over an in-memory database. The slices verify the same thing production does:
 * that the entities and the migrations describe the same schema.</p>
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:addrtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserService.class)
class AddressCrudTest {

    /** The service announces what changed; the address flow only needs the publisher to exist. */
    @TestConfiguration
    static class EventStubs {
        @Bean
        EventPublisherService eventPublisherService() {
            return Mockito.mock(EventPublisherService.class);
        }
    }

    @Autowired
    private UserService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private AddressRepository addresses;
    @Autowired
    private TestEntityManager entityManager;

    private NewAddressRequest address(String residence, String city) {
        return new NewAddressRequest(residence, null, city, "Cundinamarca", "Colombia");
    }

    private User aUser() throws FatumUserException {
        return new User.Builder()
                .awsId("aws-1")
                .email("ana@fatum.co")
                .name("Ana")
                .phoneNumber("3000000000")
                .birthDate(LocalDate.of(2000, 1, 1))
                .username("ana")
                .document("1000000001")
                .documentType(DocumentType.ID)
                .gender(Gender.FEMALE)
                .build();
    }

    /** Drops the persistence context, so the next read comes from the table and not from memory. */
    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void theSchemaMatchesTheEntities() {
        assertThat(addresses.count()).isZero();
        assertThat(users.count()).isZero();
    }

    @Test
    void storesListsAndFindsAddresses() throws FatumUserException {
        users.save(aUser());

        service.addAddress("aws-1", address("Calle 1", "Bogota"));
        service.addAddress("aws-1", address("Calle 2", "Medellin"));
        reload();

        assertThat(service.getAddresses("aws-1")).extracting(Address::getResidence)
                .containsExactly("calle 1", "calle 2");
        assertThat(service.getAddresses("aws-1")).extracting(Address::getAlias)
                .containsExactly("calle 1", "calle 2");
        assertThat(service.getAddress("aws-1", " CALLE 2 ").getCity()).isEqualTo("medellin");
    }

    @Test
    void theOrderIsStoredInTheTable() throws FatumUserException {
        users.save(aUser());
        service.addAddress("aws-1", address("Calle 1", "Bogota"));
        service.addAddress("aws-1", address("Calle 2", "Medellin"));
        reload();

        List<?> positions = entityManager.getEntityManager()
                .createNativeQuery("select position from addresses order by position")
                .getResultList();

        assertThat(positions).extracting(Object::toString).containsExactly("0", "1");
    }

    @Test
    void refusesTwoAddressesWithTheSameAlias() throws FatumUserException {
        users.save(aUser());
        service.addAddress("aws-1", address("Calle 1", "Bogota"));

        assertThatThrownBy(() -> service.addAddress("aws-1", address("calle 1", "Medellin")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.ADDRESS_EXISTS);
    }

    @Test
    void twoDifferentAliasesMayShareTheResidence() throws FatumUserException {
        users.save(aUser());
        service.addAddress("aws-1", new NewAddressRequest("Calle 1", "casa", "Bogota", "Cundinamarca", "Colombia"));
        service.addAddress("aws-1", new NewAddressRequest("Calle 1", "oficina", "Bogota", "Cundinamarca", "Colombia"));
        reload();

        assertThat(service.getAddresses("aws-1")).extracting(Address::getAlias)
                .containsExactly("casa", "oficina");
    }

    @Test
    void reportsAnUnknownAddress() throws FatumUserException {
        users.save(aUser());

        assertThatThrownBy(() -> service.getAddress("aws-1", "Calle 9"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.ADDRESS_NOT_FOUND);
    }

    @Test
    void replacingThePrincipalAddressKeepsItFirst() throws FatumUserException {
        users.save(aUser());
        service.addAddress("aws-1", address("Calle 1", "Bogota"));
        service.addAddress("aws-1", address("Calle 2", "Medellin"));

        service.updateAddress("aws-1", "Calle 1", address("Calle 3", "Cali"));
        reload();

        assertThat(service.getAddresses("aws-1")).extracting(Address::getResidence)
                .containsExactly("calle 3", "calle 2");
    }

    @Test
    void replacingASecondAddressKeepsItsPlace() throws FatumUserException {
        users.save(aUser());
        service.addAddress("aws-1", address("Calle 1", "Bogota"));
        service.addAddress("aws-1", address("Calle 2", "Medellin"));

        service.updateAddress("aws-1", "Calle 2", address("Calle 3", "Cali"));
        reload();

        assertThat(service.getAddresses("aws-1")).extracting(Address::getResidence)
                .containsExactly("calle 1", "calle 3");
    }

    @Test
    void replacingAnAddressWithAnAlreadyUsedAliasIsRejected() throws FatumUserException {
        users.save(aUser());
        service.addAddress("aws-1", address("Calle 1", "Bogota"));
        service.addAddress("aws-1", address("Calle 2", "Medellin"));

        assertThatThrownBy(() -> service.updateAddress("aws-1", "Calle 1", address("Calle 2", "Cali")))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.ADDRESS_EXISTS);
    }

    @Test
    void makingAnAddressPrincipalMovesItToTheHead() throws FatumUserException {
        users.save(aUser());
        service.addAddress("aws-1", address("Calle 1", "Bogota"));
        service.addAddress("aws-1", address("Calle 2", "Medellin"));

        service.makePrincipalAddress("aws-1", "Calle 2");
        reload();

        assertThat(service.getPrincipalAddress("aws-1").getResidence()).isEqualTo("calle 2");
        assertThat(service.getAddresses("aws-1")).extracting(Address::getResidence)
                .containsExactly("calle 2", "calle 1");
    }

    @Test
    void deletingAnAddressRemovesTheRow() throws FatumUserException {
        users.save(aUser());
        service.addAddress("aws-1", address("Calle 1", "Bogota"));
        service.addAddress("aws-1", address("Calle 2", "Medellin"));

        service.removeAddress("aws-1", "Calle 1");
        reload();

        assertThat(addresses.count()).isEqualTo(1);
        assertThat(service.getAddresses("aws-1")).extracting(Address::getResidence)
                .containsExactly("calle 2");
    }

    @Test
    void replacesTheLastAddressOfAProfessionalWithoutBreaking() throws FatumUserException {
        users.save(aUser());
        service.addAddress("aws-1", address("Calle 1", "Bogota"));
        User professional = service.getUserById("aws-1");
        professional.setRole(UserRole.PROFESSIONAL);
        users.save(professional);

        service.updateAddress("aws-1", "Calle 1", address("Calle 2", "Cali"));
        reload();

        assertThat(service.getAddresses("aws-1")).extracting(Address::getResidence)
                .containsExactly("calle 2");
    }

    @Test
    void aProfessionalCannotBeLeftWithoutAddresses() throws FatumUserException {
        users.save(aUser());
        service.addAddress("aws-1", address("Calle 1", "Bogota"));
        User professional = service.getUserById("aws-1");
        professional.setRole(UserRole.PROFESSIONAL);
        users.save(professional);

        assertThatThrownBy(() -> service.removeAddress("aws-1", "Calle 1"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PROFESSIONAL_CITY);
    }

    @Test
    void reportsAnAccountWithoutAddresses() throws FatumUserException {
        users.save(aUser());

        assertThat(service.getAddresses("aws-1")).isEmpty();

        assertThatThrownBy(() -> service.removeAddress("aws-1", "Calle 1"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.ADDRESS_NOT_FOUND);
    }
}