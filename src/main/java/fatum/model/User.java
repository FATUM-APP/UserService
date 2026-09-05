package fatum.model;

import com.fasterxml.jackson.annotation.JsonGetter;
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
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "USERS")
@Getter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class User {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "AUTH0_ID", nullable = false, length = 255)
    private String auth0Id;

    @NotBlank
    @Email
    @Size(max = 100)
    @Column(name = "EMAIL", nullable = false, unique = true, length = 100)
    private String email;

    @NotBlank
    @Size(max = 70)
    @Column(name = "NAMES", nullable = false, length = 70)
    private String names;

    @NotBlank
    @Size(max = 70)
    @Column(name = "SURNAMES", nullable = false, length = 70)
    private String surnames;

    @NotNull
    @Column(name = "BIRTH_DATE", nullable = false)
    private LocalDate birthDate;

    @Size(max = 15)
    @Setter
    @Column(name = "USERNAME", unique = true, length = 15)
    private String username;

    @Size(max = 20)
    @Setter
    @Column(name = "PHONE_NUMBER", unique = true, length = 20, nullable = false)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "ROLE", nullable = false, length = 20)
    private UserRole role = UserRole.CLIENT;

    @Column(name = "IS_AUTHENTICATED", nullable = false)
    private boolean isAuthenticated;

    @Setter
    @Column(name = "IS_ACTIVE", nullable = false)
    private boolean isActive;

    @Size(max = 30)
    @Column(name = "DOCUMENT", unique = true, length = 30)
    private String document;

    @Enumerated(EnumType.STRING)
    @Column(name = "DOCUMENT_TYPE", length = 20)
    private DocumentType documentType;


    @Size(max = 50)
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
            LocalDate birthDate) {
        this.auth0Id = auth0Id;
        this.email = email;
        this.names = names;
        this.surnames = surnames;
        this.phoneNumber = phoneNumber;
        this.birthDate = birthDate;
        this.isAuthenticated = false;
        this.isActive = true;
    }

    @JsonGetter("isAuthenticated")
    public boolean isAuthenticated() {
        return isAuthenticated;
    }

    @JsonGetter("isActive")
    public boolean isActive() {
        return isActive;
    }


    public boolean authenticate(boolean isAuthenticated) throws FatumUserException{
        if(isAuthenticated &&
                (this.document == null || this.document.isBlank() || this.documentType == null))
            throw new FatumUserException(FatumUserException.DOCUMENT_NOT_AUTHENTICATED);
        this.isAuthenticated = isAuthenticated;
        return isAuthenticated;
    }

    public void setRole(UserRole newRole) throws FatumUserException {
        if (newRole == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        if (newRole == UserRole.PROFESSIONAL && city == null) {
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        }
        this.role = newRole;
    }

    public void setCity(String newCity) throws FatumUserException {
        if (role == UserRole.PROFESSIONAL && (newCity == null || newCity.isBlank())) {
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        }
        this.city = newCity;
    }

    public void setDocument(String newDocument, DocumentType newDocumentType) throws FatumUserException {
        if (document != null) {
            throw new FatumUserException(FatumUserException.DOCUMENT_NOT_MUTABLE);
        }
        if (newDocument == null || newDocument.isBlank() || newDocumentType == null) {
            throw new FatumUserException(FatumUserException.DOCUMENT_TYPE_REQUIRED);
        }
        this.document = newDocument;
        this.documentType = newDocumentType;

    }

    public void setProfileImage(String imageKey) {
        if (profileImage == null) {
            profileImage = new ProfileImage(imageKey, this);
        } else {
            profileImage.replaceImageKey(imageKey);
        }
    }
}
