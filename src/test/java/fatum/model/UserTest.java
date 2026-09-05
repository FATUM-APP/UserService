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
    @DisplayName("Should authenticate when a valid document exists")
    void shouldAuthenticateWithDocument() throws FatumUserException {
        User user = buildUser();
        user.setDocument("1000271422", DocumentType.ID);

        assertTrue(user.authenticate(true));
        assertTrue(user.isAuthenticated());
    }

    @Test
    @DisplayName("Should reject authentication without a document")
    void shouldRejectAuthenticationWithoutDocument() {
        User user = buildUser();

        FatumUserException exception = assertThrows(
                FatumUserException.class,
                () -> user.authenticate(true));

        assertEquals(FatumUserException.DOCUMENT_NOT_AUTHENTICATED, exception.getMessage());
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
    @DisplayName("Should serialize only the current authentication properties")
    void shouldSerializeCurrentBooleanProperties() throws Exception {
        User user = buildUser();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(user));

        assertTrue(json.has("isAuthenticated"));
        assertTrue(json.has("isActive"));
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
                "+573001112233",
                LocalDate.of(1995, 5, 10));
    }
}
