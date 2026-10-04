package fatum.model;

import fatum.exception.FatumUserException;
import fatum.model.constant.Country;
import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    /**
     * Addresses of the account, the first one being the principal one.
     *
     * <p>It is a {@code List} and not a {@code SequencedSet} for two reasons. Hibernate builds the
     * concrete collection itself and has no implementation of {@code SequencedSet} to build, so with
     * the interface the mapping fails as soon as the collection is loaded ("cannot set
     * java.util.HashSet"). And the order has to survive the JVM: {@code @OrderColumn} stores the
     * position of each address in the table, so "the principal one is the first" becomes a fact of
     * the database and not of the session.</p>
     */
    @OrderColumn(name = "POSITION")
    private List<Address> addressList;

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


    private User(Builder builder) throws FatumUserException {
        this.awsId = requireText(builder.awsId);
        this.email = requireText(builder.email).toLowerCase(Locale.ROOT);
        this.name = requireText(builder.name).toUpperCase(Locale.ROOT);
        setPhoneNumber(builder.phoneNumber);
        this.birthDate = (LocalDate) validateNonNullObject(builder.birthDate);
        setUsername(builder.username);
        this.document = requireText(builder.document).toUpperCase(Locale.ROOT);
        this.documentType = (DocumentType) validateNonNullObject(builder.documentType);
        this.verificationStatus = VerificationStatus.VERIFIED;
        this.isActive = true;
        this.gender = (Gender) validateNonNullObject(builder.gender);
        this.addressList = new ArrayList<>();
    }

    public void setUsername(String newUsername) throws FatumUserException {
        this.username = requireText(newUsername).toLowerCase(Locale.ROOT);
    }

    public void setPhoneNumber(String newPhoneNumber) throws FatumUserException {
        this.phoneNumber = requireText(newPhoneNumber);
    }

    public void addAddress(Address address) {
        addressList.add(address);
    }

    public void removeAddress(Address address) throws FatumUserException {
        if (role == UserRole.PROFESSIONAL && addressList.size() == 1)
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        addressList.remove(address);
    }

    /**
     * Moves an address to the head of the list, which is what makes it the principal one.
     *
     * <p>Removing it first is not decorative: {@code List.addFirst} inserts, so passing an address
     * that is already in the list would add a second copy of it instead of moving it. A set could
     * not hold the duplicate and the move was implicit; the collection is a list now, so the move
     * is written step by step.</p>
     */
    public void makePrincipalAddress(Address address) {
        addressList.remove(address);
        addressList.addFirst(address);
    }

    public Address getPrincipalAddress() {
        return addressList.getFirst();
    }

    public boolean hasAddress() {
        return !addressList.isEmpty();
    }

    public boolean addressInList(Address address) {
        return addressList.contains(address);
    }

    public void setRole(UserRole newRole) throws FatumUserException {
        if (newRole == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        if (newRole == UserRole.PROFESSIONAL && addressList.isEmpty()) {
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        }
        this.role = newRole;
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

    /**
     * Gives the account its access back.
     *
     * <p>It only flips the local flag: restoring the access in the user pool belongs to whoever
     * reactivates, and the service does it right after saving, the same way it revokes it when the
     * account is deactivated.</p>
     */
    public void activate() {
        this.isActive = true;
    }

    private Object validateNonNullObject(Object value) throws FatumUserException {
        if (value == null) throw new FatumUserException(FatumUserException.NULL_VALUE);
        return value;
    }

    private String requireText(String value) throws FatumUserException {
        if (value == null || value.isBlank()) throw new FatumUserException(FatumUserException.NULL_VALUE);
        return value.trim();
    }

    public static class Builder {
        private String awsId;
        private String email;
        private String name;
        private String phoneNumber;
        private LocalDate birthDate;
        private String username;
        private String document;
        private DocumentType documentType;
        private Gender gender;

        public Builder awsId(String awsId) { this.awsId = awsId; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder phoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; return this; }
        public Builder birthDate(LocalDate birthDate) { this.birthDate = birthDate; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder document(String document) { this.document = document; return this; }
        public Builder documentType(DocumentType documentType) { this.documentType = documentType; return this; }
        public Builder gender(Gender gender) { this.gender = gender; return this; }

        public User build() throws FatumUserException {
            return new User(this);
        }
    }
}



