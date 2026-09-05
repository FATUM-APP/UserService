package fatum.model;

import fatum.exception.FatumUserException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDate;
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

    @Column(name = "NAMES", nullable = false, length = 70)
    private String names;

    @Column(name = "SURNAMES", nullable = false, length = 70)
    private String surnames;

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

    @Setter
    @Column(name = "IS_ACTIVE", nullable = false)
    private boolean isActive;

    @Column(name = "DOCUMENT", unique = true, length = 10)
    private String document;

    @Enumerated(EnumType.STRING)
    @Column(name = "DOCUMENT_TYPE", length = 20)
    private DocumentType documentType;

    @Column(name = "CITY", length = 50)
    private String city;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    private ProfileImage profileImage;

    public User(
            String auth0Id,
            String email,
            String names,
            String surnames,
            String phoneNumber,
            LocalDate birthDate,
            String username) {
        this.auth0Id = auth0Id;
        this.email = normalize(email, "1");
        this.names = normalize(names, "names");
        this.surnames = normalize(surnames, "names");
        this.phoneNumber = normalize(phoneNumber, "3");
        this.birthDate = birthDate;
        this.username = normalize(username, "1");
        this.isAuthenticated = false;
        this.isActive = true;
    }

    public void setUsername(String newUsername) throws FatumUserException {
        this.username = requireText(newUsername, "username");
    }

    public void setPhoneNumber(String newPhoneNumber) throws FatumUserException {
        this.phoneNumber = requireText(newPhoneNumber, "phoneNumber");
    }

    public void setRole(UserRole newRole) throws FatumUserException {
        if (newRole == null) throw new FatumUserException(FatumUserException.NULL_VALUE);
        if (newRole == UserRole.PROFESSIONAL && (city == null || city.isBlank())) {
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        }
        this.role = newRole;
    }

    public void setCity(String newCity) throws FatumUserException {
        if (role == UserRole.PROFESSIONAL && (newCity == null || newCity.isBlank())) {
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        }
        this.city = requireText(newCity, "city");
    }

    public boolean authenticate(boolean authenticated) throws FatumUserException {
        if (!this.isActive) throw new FatumUserException(FatumUserException.INACTIVE);
        if (authenticated && (document == null || document.isBlank() || documentType == null)) {
            throw new FatumUserException(FatumUserException.DOCUMENT_NOT_AUTHENTICATED);
        }
        this.isAuthenticated = authenticated;
        return authenticated;
    }

    public void setDocument(String newDocument, DocumentType newDocumentType) throws FatumUserException {
        if (document != null) {
            throw new FatumUserException(FatumUserException.DOCUMENT_NOT_MUTABLE);
        }
        if ((newDocument == null || newDocument.isBlank() || newDocumentType == null)) {
            throw new FatumUserException(FatumUserException.DOCUMENT_TYPE_REQUIRED);
        }
        this.document = newDocument;
        this.documentType = newDocumentType;
    }


    public void setProfileImage(String imageKey) throws FatumUserException {
        String normalizedKey = requireText(imageKey, "imageKey");
        if (profileImage == null) {
            profileImage = new ProfileImage(normalizedKey, this);
        } else {
            profileImage.replaceImageKey(normalizedKey);
        }
    }

    private String requireText(String value, String fieldName) throws FatumUserException {
        if (value == null || value.isBlank()) {
            throw new FatumUserException(fieldName + ": " + FatumUserException.NULL_VALUE);
        }
        return normalize(value, "1");
    }

    private String normalize(String value, String option) {
        if (option.equals("names")) return value.trim().toUpperCase();
            else return value.trim().toLowerCase();

    }


}
