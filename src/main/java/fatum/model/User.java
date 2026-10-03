package fatum.model;

import fatum.exception.FatumUserException;
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

@Entity
@Table(name = "USERS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class User {

    /** The widest value of {@link Gender} ("FEMALE") is six characters, not five. */
    private static final int GENDER_COLUMN_LENGTH = 6;

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

    /**
     * Identity verification state. It replaces the former {@code isAuthenticated} boolean, which
     * could not express "the system could not decide, a human has to look at it".
     *
     * <p>The document pipeline is not running for now, so every account is born VERIFIED. The whole
     * state machine stays in place so the feature can be switched back on without another migration.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "VERIFICATION_STATUS", nullable = false, length = 20)
    private VerificationStatus verificationStatus = VerificationStatus.VERIFIED;

    @Column(name = "IS_ACTIVE", nullable = false)
    private boolean isActive;

    @Column(name = "DOCUMENT", nullable = false, unique = true, length = 30)
    private String document;

    @Enumerated(EnumType.STRING)
    @Column(name = "GENDER", nullable = false, length = GENDER_COLUMN_LENGTH)
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
            Gender gender
            ) throws FatumUserException {
        this.awsId = requireText(awsId);
        this.email = requireText(email).toLowerCase(Locale.ROOT);
        this.name = requireText(name).toUpperCase(Locale.ROOT);
        setPhoneNumber(phoneNumber);
        this.birthDate = (LocalDate) validateNonNullObject(birthDate);
        setUsername(username);
        this.document = requireText(document);
        this.documentType = (DocumentType) validateNonNullObject(documentType);
        this.verificationStatus = VerificationStatus.VERIFIED;
        this.isActive = true;
        this.country = Country.COLOMBIA;
        this.gender = (Gender) validateNonNullObject(gender);
    }

    public void setUsername(String newUsername) throws FatumUserException {
        this.username = requireText(newUsername).toLowerCase(Locale.ROOT);
    }

    public void setPhoneNumber(String newPhoneNumber) throws FatumUserException {
        this.phoneNumber = requireText(newPhoneNumber);
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

    /**
     * Records the identity verification state of the account.
     *
     * @param newStatus state to store; never null
     * @throws FatumUserException when the state is missing
     */
    public void markVerificationStatus(VerificationStatus newStatus) throws FatumUserException {
        if (newStatus == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        this.verificationStatus = newStatus;
    }

    public boolean isVerified() {
        return verificationStatus == VerificationStatus.VERIFIED;
    }

    public void deactivate() {
        this.isActive = false;
    }

    private Object validateNonNullObject(Object value) throws FatumUserException {
        if (value == null) throw new FatumUserException(FatumUserException.NULL_VALUE);
        return value;
    }

    private String requireText(String value) throws FatumUserException {
        if (value == null || value.isBlank()) throw new FatumUserException(FatumUserException.NULL_VALUE);
        return value.trim();
    }
}
