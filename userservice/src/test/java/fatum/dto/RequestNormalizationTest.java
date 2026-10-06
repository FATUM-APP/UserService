package fatum.dto;

import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request DTOs are the single place where text is cleaned, so these tests describe what every
 * other layer is allowed to assume.
 */
class RequestNormalizationTest {

    @Test
    void theSignupRequestArrivesClean() {
        CreateUserRequest request = new CreateUserRequest(
                "  us-east-1:0a1b2c3d ",
                "  Camilo.Castano@Fatum.COM ",
                "  camilo castaño ",
                " +57 300 111 2233 ",
                LocalDate.of(1998, 5, 10),
                "  CCastano46 ",
                "  1020abc ",
                DocumentType.ID,
                Gender.MALE);

        assertThat(request.awsId()).isEqualTo("us-east-1:0a1b2c3d");
        assertThat(request.email()).isEqualTo("camilo.castano@fatum.com");
        assertThat(request.name()).isEqualTo("CAMILO CASTAÑO");
        assertThat(request.phoneNumber()).isEqualTo("+57 300 111 2233");
        assertThat(request.username()).isEqualTo("ccastano46");
        assertThat(request.document()).isEqualTo("1020ABC");
    }

    @Test
    void theUpdateRequestAndItsAddressArriveClean() {
        NewAddressRequest address = new NewAddressRequest(
                "  Calle 1 # 2-3 ", "  Casa ", "  BOGOTA ", "  Cundinamarca ", "  Colombia ");
        UserUpdateRequest request = new UserUpdateRequest("  NewJane ", " +57 300 111 2233 ", address);

        assertThat(request.username()).isEqualTo("newjane");
        assertThat(request.phoneNumber()).isEqualTo("+57 300 111 2233");
        assertThat(request.newAddress()).isSameAs(address);
        assertThat(address.residence()).isEqualTo("calle 1 # 2-3");
        assertThat(address.alias()).isEqualTo("casa");
        assertThat(address.city()).isEqualTo("bogota");
        assertThat(address.state()).isEqualTo("cundinamarca");
        assertThat(address.country()).isEqualTo("colombia");
    }

    @Test
    void aFieldOfSpacesBecomesEmptySoValidationRejectsIt() {
        CreateUserRequest request = new CreateUserRequest(
                "   ", "user@fatum.com", "Jane Doe", "+573001112233",
                LocalDate.of(1998, 5, 10), "jane", "1020", DocumentType.ID, Gender.FEMALE);

        assertThat(request.awsId()).isEmpty();
    }

    @Test
    void anOmittedFieldStaysOmitted() {
        UserUpdateRequest request = new UserUpdateRequest(null, null, null);

        assertThat(request.username()).isNull();
        assertThat(request.phoneNumber()).isNull();
        assertThat(request.newAddress()).isNull();
    }

    @Test
    void aLookupKeyLosesItsPaddingAndItsBlanks() {
        assertThat(TextNormalizer.trimOrNull("  aws-user-1 ")).isEqualTo("aws-user-1");
        assertThat(TextNormalizer.trimOrNull("   ")).isNull();
        assertThat(TextNormalizer.trimOrNull(null)).isNull();
    }

    @Test
    void everyNormalizerMethodIsNullSafe() {
        assertThat(TextNormalizer.trim(null)).isNull();
        assertThat(TextNormalizer.lower(null)).isNull();
        assertThat(TextNormalizer.upper(null)).isNull();
    }
}