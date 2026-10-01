package fatum.model;

import fatum.model.constant.Country;
import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;
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

/**
 * Persistent aggregate root of a Fatum user.
 *
 * <p>The entity holds state and state transitions only. Business rules (uniqueness, minimum age,
 * mandatory city for professionals, document required to verify) live in {@code UserService}, which is
 * the single place that raises {@code FatumUserException}; the guards kept here are structural and
 * fail fast on a programming mistake.</p>
 */
@Entity
@Table(name = "USERS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class User {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "AWS_ID", nullable = false, length = 255)
    private String awsId;

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

    /** Identity verification state; replaces the former boolean flag. */
    @Enumerated(EnumType.STRING)
    @Column(name = "VERIFICATION_STATUS", nullable = false, length = 20)
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    @Column(name = "IS_ACTIVE", nullable = false)
    private boolean isActive;

    @Column(name = "DOCUMENT", nullable = false, unique = true, length = 30)
    private String document;

    @Enumerated(EnumType.STRING)
    @Column(name = "GENDER", nullable = false, length = 6)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "DOCUMENT_TYPE", nullable = false, length = 20)
    private DocumentType documentType;

    @Column(name = "CITY", length = 50)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "COUNTRY", nullable = false, length = 50)
    private Country country = Country.COLOMBIA;

    public User(
            String awsId,
            String email,
            String name,
            String phoneNumber,
            LocalDate birthDate,
            String username,
            String document,
            DocumentType documentType,
            Gender gender) {
        this.awsId = requireText(awsId, "awsId");
        this.email = requireText(email, "email").toLowerCase(Locale.ROOT);
        this.name = requireText(name, "name").toUpperCase(Locale.ROOT);
        this.phoneNumber = requireText(phoneNumber, "phoneNumber");
        this.birthDate = requireNonNull(birthDate, "birthDate");
        this.username = requireText(username, "username").toLowerCase(Locale.ROOT);
        this.document = requireText(document, "document");
        this.documentType = requireNonNull(documentType, "documentType");
        this.gender = requireNonNull(gender, "gender");
        this.role = UserRole.CLIENT;
        this.verificationStatus = VerificationStatus.UNVERIFIED;
        this.isActive = true;
        this.country = Country.COLOMBIA;
    }

    public void setUsername(String newUsername) {
        this.username = requireText(newUsername, "username").toLowerCase(Locale.ROOT);
    }

    public void setPhoneNumber(String newPhoneNumber) {
        this.phoneNumber = requireText(newPhoneNumber, "phoneNumber");
    }

    public void setRole(UserRole newRole) {
        this.role = requireNonNull(newRole, "role");
    }

    public void setCity(String newCity) {
        this.city = newCity == null || newCity.isBlank() ? null : newCity.trim();
    }

    /** Records the outcome of the verification pipeline. */
    public void markVerificationStatus(VerificationStatus newStatus) {
        this.verificationStatus = requireNonNull(newStatus, "verificationStatus");
    }

    public boolean isVerified() {
        return verificationStatus == VerificationStatus.VERIFIED;
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

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
