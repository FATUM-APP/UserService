package fatum.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fatum.exception.FatumUserException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("User domain tests")
class UserTest {

    @Test
    @DisplayName("Should authenticate when every required field is present")
    void shouldAuthenticateCompleteUser() throws FatumUserException {
        User user = buildUser();
        user.setUsername("ccastano46");
        user.setPhoneNumber("+573001112233");
        user.setDocument("1000271422", DocumentType.ID);
        user.authenticateDocument();

        assertTrue(user.authenticate());
        assertTrue(user.isAuthenticated());
    }

    @Test
    @DisplayName("Should remain unauthenticated until document verification")
    void shouldRequireDocumentVerification() throws FatumUserException {
        User user = buildUser();
        user.setUsername("ccastano46");
        user.setPhoneNumber("+573001112233");
        user.setDocument("1000271422", DocumentType.ID);

        assertFalse(user.authenticate());
    }

    @Test
    @DisplayName("Should reject professional role without city")
    void shouldRejectProfessionalWithoutCity() {
        User user = buildUser();

        FatumUserException exception = assertThrows(
                FatumUserException.class,
                () -> user.setRole(UserRole.PROFESSIONAL));

        assertEquals(FatumUserException.PROFESSIONAL_CITY, exception.getMessage());
    }

    @Test
    @DisplayName("Should serialize historical boolean property names without duplicates")
    void shouldSerializeExpectedBooleanProperties() throws Exception {
        User user = buildUser();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(user));

        assertTrue(json.has("isAuthenticated"));
        assertTrue(json.has("isActive"));
        assertTrue(json.has("documentIsAuthenticated"));
        assertFalse(json.has("authenticated"));
        assertFalse(json.has("active"));
    }

    @Test
    @DisplayName("Should not allow document mutation")
    void shouldKeepDocumentImmutable() throws FatumUserException {
        User user = buildUser();
        user.setDocument("1000271422", DocumentType.ID);

        FatumUserException exception = assertThrows(
                FatumUserException.class,
                () -> user.setDocument("999999999", DocumentType.ID));

        assertEquals(FatumUserException.DOCUMENT_NOT_MUTABLE, exception.getMessage());
    }

    private User buildUser() {
        return new User(
                "auth0|domain-test",
                "domain@example.com",
                "Camilo",
                "Castaño",
                LocalDate.of(1995, 5, 10));
    }
}
