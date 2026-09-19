package fatum.model;

import fatum.exception.FatumUserException;
import fatum.model.constant.Country;
import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import fatum.model.constant.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

@Entity
@Table(name = "USERS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class User {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "AUTH0_ID", nullable = false, length = 255)
    private String auth0Id;

    @Column(name = "EMAIL", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "COMPLETE_NAME", nullable = false, length = 142)
    private String name;

    @Column(name = "BIRTH_DATE", nullable = false)
    private LocalDate birthDate;

    @Column(name = "USERNAME", nullable = false, unique = true, length = 15)
    private String username;

    @Column(name = "PHONE_NUMBER", nullable = false, unique = true, length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "ROLE", nullable = false, length = 20)
    private UserRole role = UserRole.CLIENT;

    @Column(name = "IS_AUTHENTICATED", nullable = false)
    private boolean isAuthenticated;

    @Column(name = "IS_ACTIVE", nullable = false)
    private boolean isActive;

    @Column(name = "DOCUMENT", nullable = false, unique = true, length = 30)
    private String document;

    @Enumerated(EnumType.STRING)
    @Column(name = "GENDER", nullable = false, length = 5)
    private Gender  gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "DOCUMENT_TYPE", nullable = false, length = 20)
    private DocumentType documentType;

    @Column(name = "CITY", length = 50)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "COUNTRY", nullable = false, length = 50)
    private Country country = Country.COLOMBIA;

    public User(
            String auth0Id,
            String email,
            String name,
            String phoneNumber,
            LocalDate birthDate,
            String username,
            String document,
            DocumentType documentType
            ) {
        this.auth0Id = requireText(auth0Id, "auth0Id");
        this.email = requireText(email, "email").toLowerCase(Locale.ROOT);
        this.name = requireText(name, "name").toUpperCase(Locale.ROOT);
        this.phoneNumber = requireText(phoneNumber, "phoneNumber");
        this.birthDate = Objects.requireNonNull(birthDate, "birthDate is required");
        this.username = requireText(username, "username");
        this.document = requireText(document, "document");
        this.documentType = Objects.requireNonNull(documentType, "documentType is required");
        this.isAuthenticated = false;
        this.isActive = true;
        this.country = Country.COLOMBIA;
    }

    public void setUsername(String newUsername) {
        this.username = requireText(newUsername, "username");
    }

    public void setPhoneNumber(String newPhoneNumber) {
        this.phoneNumber = requireText(newPhoneNumber, "phoneNumber");
    }

    public void setRole(UserRole newRole) throws FatumUserException {
        if (newRole == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        if (newRole == UserRole.PROFESSIONAL && (city == null || city.isBlank())) {
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        }
        this.role = newRole;
    }

    public void setCity(String newCity) throws FatumUserException {
        if (role == UserRole.PROFESSIONAL && (newCity == null || newCity.isBlank())) {
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        }
        this.city = newCity == null || newCity.isBlank() ? null : newCity.trim();
    }

    public boolean authenticate(boolean authenticated) throws FatumUserException {
        if (!isActive) {
            throw new FatumUserException(FatumUserException.INACTIVE);
        }
        if (authenticated && (document.isBlank() || documentType == null)) {
            throw new FatumUserException(FatumUserException.DOCUMENT_NOT_AUTHENTICATED);
        }
        this.isAuthenticated = authenticated;
        return authenticated;
    }

    public void deactivate() {
        this.isActive = false;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
