package fatum.service;

import fatum.dto.NewAddressRequest;
import fatum.dto.UserUpdateRequest;
import fatum.exception.FatumUserException;
import fatum.model.Address;
import fatum.model.User;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;
import fatum.repository.AddressRepository;
import fatum.repository.UserRepository;
import fatum.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The business rules of the user aggregate.
 *
 * <p>The user pool is not mocked here because the service no longer talks to it: what leaves this
 * service is an event, so that is what these tests check. The publisher is a mock, and the assertions
 * read either the state it left behind or the announcement it made.</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String USER_ID = Fixtures.USER_ID;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private EventPublisherService eventPublisherService;

    @InjectMocks
    private UserService service;

    /**
     * The identifier comes from the database, so a fixture has to be given one.
     *
     * <p>Two unsaved addresses are equal to each other, because the entity compares by identifier and
     * both are still null. Moving one to the head of the list removes the first equal element, so
     * without an identifier the move would take the wrong address out.</p>
     */
    private static Address persisted(Address address, String id) {
        try {
            var field = Address.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(address, id);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("The fixture address needs an identifier", failure);
        }
        return address;
    }

    // -------------------------------------------------------------------- create

    @Test
    void aNewAccountIsStoredAndAnnounced() throws FatumUserException {
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User stored = service.createUser(Fixtures.createRequest());

        assertThat(stored.getAwsId()).isEqualTo(USER_ID);
        assertThat(stored.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(stored.getRole()).isEqualTo(UserRole.CLIENT);
        assertThat(stored.getAddressList()).isEmpty();
        verify(eventPublisherService)
                .verificationStatusChanged(USER_ID, stored.getEmail(), VerificationStatus.VERIFIED);
    }

    // ------------------------------------------------------------------ validate

    @Test
    void aUserWithoutBirthDateIsRejected() {
        assertThatThrownBy(() -> service.validateUser(null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NULL_VALUE);
    }

    @Test
    void anUnderageUserIsRejected() {
        User minor = Fixtures.user(USER_ID, LocalDate.now().minusYears(17));

        assertThatThrownBy(() -> service.validateUser(minor))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.UNDERAGE_USER);
    }

    @Test
    void anAdultUserWithFreeValuesPassesValidation() throws FatumUserException {
        User candidate = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(null);
        when(userRepository.findByEmailIgnoreCase(candidate.getEmail())).thenReturn(null);
        when(userRepository.findByPhoneNumber(candidate.getPhoneNumber())).thenReturn(null);
        when(userRepository.findByUsernameIgnoreCase(candidate.getUsername())).thenReturn(null);
        when(userRepository.findByDocumentIgnoreCase(candidate.getDocument())).thenReturn(null);

        service.validateUser(candidate);
    }

    @Test
    void aDuplicatedAccountIsRejected() {
        User candidate = Fixtures.user();
        when(userRepository.findByAwsId(candidate.getAwsId())).thenReturn(Fixtures.user());

        assertThatThrownBy(() -> service.validateUser(candidate))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_ALREADY_EXISTS);
    }

    @Test
    void aDuplicatedEmailIsRejected() {
        User candidate = Fixtures.user();
        when(userRepository.findByAwsId(anyString())).thenReturn(null);
        when(userRepository.findByEmailIgnoreCase(candidate.getEmail()))
                .thenReturn(Fixtures.user("aws-user-2"));

        assertThatThrownBy(() -> service.validateUser(candidate))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.EMAIL_EXISTS);
    }

    @Test
    void aDuplicatedUsernameIsRejected() {
        User candidate = Fixtures.user();
        when(userRepository.findByAwsId(anyString())).thenReturn(null);
        when(userRepository.findByEmailIgnoreCase(candidate.getEmail())).thenReturn(null);
        when(userRepository.findByPhoneNumber(candidate.getPhoneNumber())).thenReturn(null);
        when(userRepository.findByUsernameIgnoreCase(candidate.getUsername()))
                .thenReturn(Fixtures.user("aws-user-2"));

        assertThatThrownBy(() -> service.validateUser(candidate))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USERNAME_EXISTS);
    }

    @Test
    void aDuplicatedPhoneNumberIsRejected() {
        User candidate = Fixtures.user();
        when(userRepository.findByAwsId(anyString())).thenReturn(null);
        when(userRepository.findByEmailIgnoreCase(candidate.getEmail())).thenReturn(null);
        when(userRepository.findByPhoneNumber(candidate.getPhoneNumber()))
                .thenReturn(Fixtures.user("aws-user-2"));

        assertThatThrownBy(() -> service.validateUser(candidate))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PHONE_EXISTS);
    }

    @Test
    void aDuplicatedDocumentIsRejected() {
        User candidate = Fixtures.user();
        when(userRepository.findByAwsId(anyString())).thenReturn(null);
        when(userRepository.findByEmailIgnoreCase(candidate.getEmail())).thenReturn(null);
        when(userRepository.findByPhoneNumber(candidate.getPhoneNumber())).thenReturn(null);
        when(userRepository.findByUsernameIgnoreCase(candidate.getUsername())).thenReturn(null);
        when(userRepository.findByDocumentIgnoreCase(candidate.getDocument()))
                .thenReturn(Fixtures.user("aws-user-2"));

        assertThatThrownBy(() -> service.validateUser(candidate))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.DOCUMENT_EXISTS);
    }

    @Test
    void reusingAValueOfTheSameAccountIsNotAConflict() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        // Both lookups return a row that belongs to this very account, so neither is a conflict.
        when(userRepository.findByUsernameIgnoreCase("other")).thenReturn(existing);
        when(userRepository.findByPhoneNumber("+573000000001")).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        User updated = service.updateUser(USER_ID,
                new UserUpdateRequest("other", "+573000000001", null));

        assertThat(updated.getUsername()).isEqualTo("other");
        assertThat(updated.getPhoneNumber()).isEqualTo("+573000000001");
    }

    // ------------------------------------------------------------------- lookups

    @Test
    void findsAnAccountById() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThat(service.getUserById("  " + USER_ID + "  ")).isSameAs(existing);
    }

    @Test
    void anUnknownIdIsReported() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getUserById(USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void aBlankIdNeverReachesARealLookup() {
        assertThatThrownBy(() -> service.getUserById("   "))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void findsAnAccountByUsername() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByUsernameIgnoreCase(existing.getUsername())).thenReturn(existing);

        assertThat(service.getUserByUsername(existing.getUsername())).isSameAs(existing);
    }

    @Test
    void anUnknownUsernameIsReported() {
        when(userRepository.findByUsernameIgnoreCase("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.getUserByUsername("ghost"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void findsAnAccountByEmail() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);

        assertThat(service.getUserByEmail(existing.getEmail())).isSameAs(existing);
    }

    @Test
    void anUnknownEmailIsReported() {
        when(userRepository.findByEmailIgnoreCase("ghost@fatum.com")).thenReturn(null);

        assertThatThrownBy(() -> service.getUserByEmail("ghost@fatum.com"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void searchesByName() {
        List<User> expected = List.of(Fixtures.user());
        when(userRepository.findByNameIgnoreCase("Jane Doe")).thenReturn(expected);

        assertThat(service.getUsersByName(" Jane Doe ")).isEqualTo(expected);
    }

    @Test
    void thePublicDirectoryOnlyShowsAccountsThatStillHaveAccess() {
        when(userRepository.findByNameIgnoreCaseAndIsActive("Jane Doe", true))
                .thenReturn(List.of(Fixtures.user()));

        assertThat(service.getActiveUsersByName(" Jane Doe ")).hasSize(1);
    }

    @Test
    void aDeactivatedAccountIsRefusedAsInactive() {
        User deactivated = Fixtures.user();
        deactivated.deactivate();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(deactivated);

        assertThatThrownBy(() -> service.getActiveUserById(USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INACTIVE);
    }

    @Test
    void anActiveAccountIsReadNormally() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);

        assertThat(service.getActiveUserByEmail(existing.getEmail())).isSameAs(existing);
    }

    @Test
    void listsTheProfessionalsThatStillHaveAccess() throws FatumUserException {
        User professional = Fixtures.user();
        professional.addAddress(Fixtures.address(professional));
        professional.setRole(UserRole.PROFESSIONAL);
        when(userRepository.findByRoleAndIsActive(UserRole.PROFESSIONAL, true))
                .thenReturn(List.of(professional));

        assertThat(service.getProfessionals()).containsExactly(professional);
    }

    @Test
    void reportsWhetherAnAccountIsActive() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);

        assertThat(service.isUserActiveByEmail(existing.getEmail())).isTrue();

        existing.deactivate();
        assertThat(service.isUserActiveByEmail(existing.getEmail())).isFalse();
    }

    @Test
    void reportsTheVerificationStatusOfAnAccount() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);

        assertThat(service.getVerificationStatus(existing.getEmail()))
                .isEqualTo(VerificationStatus.VERIFIED);
    }

    // -------------------------------------------------------------------- update

    @Test
    void updatesTheUsernameAndThePhoneNumber() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.findByUsernameIgnoreCase("newjane")).thenReturn(null);
        when(userRepository.findByPhoneNumber("+573001112233")).thenReturn(null);
        when(userRepository.save(existing)).thenReturn(existing);

        User updated = service.updateUser(USER_ID,
                new UserUpdateRequest("newjane", "+573001112233", null));

        assertThat(updated.getUsername()).isEqualTo("newjane");
        assertThat(updated.getPhoneNumber()).isEqualTo("+573001112233");
        assertThat(updated.getRole()).isEqualTo(UserRole.CLIENT);
        verify(eventPublisherService, never()).userBecameProfessional(anyString(), anyString());
    }

    @Test
    void aUsernameAlreadyTakenIsRejected() {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.findByUsernameIgnoreCase("taken")).thenReturn(Fixtures.user("aws-user-2"));

        assertThatThrownBy(() -> service.updateUser(USER_ID,
                new UserUpdateRequest("taken", null, null)))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USERNAME_EXISTS);
    }

    @Test
    void aPhoneNumberAlreadyTakenIsRejected() {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.findByPhoneNumber("+573009998877"))
                .thenReturn(Fixtures.user("aws-user-2"));

        assertThatThrownBy(() -> service.updateUser(USER_ID,
                new UserUpdateRequest(null, "+573009998877", null)))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PHONE_EXISTS);
    }

    @Test
    void keepingTheSameValuesChangesNothing() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        User updated = service.updateUser(USER_ID,
                new UserUpdateRequest(existing.getUsername().toUpperCase(), existing.getPhoneNumber(), null));

        assertThat(updated.getUsername()).isEqualTo(existing.getUsername());
        assertThat(updated.getAddressList()).isEmpty();
    }

    @Test
    void addsTheAddressThatTheUpdateCarries() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(addressRepository.existsByUserAwsIdAndAlias(USER_ID, "casa")).thenReturn(false);
        when(userRepository.save(existing)).thenReturn(existing);

        User updated = service.updateUser(USER_ID,
                new UserUpdateRequest(null, null, Fixtures.addressRequest()));

        assertThat(updated.getAddressList()).hasSize(1);
        assertThat(updated.getAddressList().getFirst().getAlias()).isEqualTo("casa");
    }

    // ----------------------------------------------------------- role transitions

    @Test
    void becomingAProfessionalIsAnnounced() throws FatumUserException {
        User existing = Fixtures.user();
        existing.addAddress(Fixtures.address(existing));
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        service.becomeProfessional(USER_ID);

        assertThat(existing.getRole()).isEqualTo(UserRole.PROFESSIONAL);
        verify(eventPublisherService).userBecameProfessional(USER_ID, existing.getEmail());
    }

    @Test
    void anUnverifiedAccountCannotBecomeAProfessional() throws FatumUserException {
        User existing = Fixtures.user();
        existing.markVerificationStatus(VerificationStatus.UNVERIFIED);
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThatThrownBy(() -> service.becomeProfessional(USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.FORBIDDEN);
    }

    @Test
    void aProfessionalWithoutAnAddressIsRejected() {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThatThrownBy(() -> service.becomeProfessional(USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PROFESSIONAL_CITY);
    }

    @Test
    void becomingAProfessionalWithAnAddressMakesItPrincipalAndAnnouncesIt() throws FatumUserException {
        User existing = Fixtures.user();
        existing.addAddress(persisted(
                new Address("calle 0", "vieja", "cali", "colombia", existing, "valle"), "addr-vieja"));
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(addressRepository.existsByUserAwsIdAndAlias(USER_ID, "casa")).thenReturn(false);
        when(userRepository.save(existing)).thenReturn(existing);

        service.becomeProfessionalWithAddress(USER_ID, Fixtures.addressRequest());

        assertThat(existing.getRole()).isEqualTo(UserRole.PROFESSIONAL);
        assertThat(existing.getAddressList()).extracting(Address::getAlias)
                .containsExactly("casa", "vieja");
        assertThat(existing.getPrincipalAddress().getAlias()).isEqualTo("casa");
        verify(eventPublisherService)
                .professionalPrincipalAddressChanged(USER_ID, existing.getEmail(), "casa");
        verify(eventPublisherService).userBecameProfessional(USER_ID, existing.getEmail());
    }

    @Test
    void goingBackToClientIsAnnounced() throws FatumUserException {
        User existing = Fixtures.user();
        existing.addAddress(Fixtures.address(existing));
        existing.setRole(UserRole.PROFESSIONAL);
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        service.becomeClient(USER_ID);

        assertThat(existing.getRole()).isEqualTo(UserRole.CLIENT);
        verify(eventPublisherService).professionalBecameClient(USER_ID, existing.getEmail());
    }

    @Test
    void anAccountThatWasAlreadyAClientAnnouncesNothing() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        service.becomeClient(USER_ID);

        verify(eventPublisherService, never()).professionalBecameClient(anyString(), anyString());
        verify(eventPublisherService, never()).userBecameProfessional(anyString(), anyString());
    }

    // ------------------------------------------------------------------- address

    @Test
    void readsThePrincipalAddressOfTheAccount() throws FatumUserException {
        User existing = Fixtures.user();
        existing.addAddress(Fixtures.address(existing));
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThat(service.getPrincipalAddress(USER_ID).getAlias()).isEqualTo("casa");
    }

    @Test
    void reportsAnAccountWithoutAddresses() {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThatThrownBy(() -> service.getPrincipalAddress(USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.ADDRESS_NOT_FOUND);
    }

    @Test
    void listsTheAddressesInOrder() throws FatumUserException {
        User existing = Fixtures.user();
        existing.addAddress(Fixtures.address(existing));
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThat(service.getAddresses(USER_ID)).hasSize(1);
    }

    @Test
    void movesAnAddressToThePrincipalPlace() throws FatumUserException {
        User existing = Fixtures.user();
        existing.addAddress(Fixtures.address(existing));
        Address second = persisted(
                new Address("calle 2", "oficina", "medellin", "colombia", existing, "antioquia"), "addr-2");
        existing.addAddress(second);
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(addressRepository.findByUserAwsIdAndAlias(USER_ID, "oficina")).thenReturn(second);
        when(userRepository.save(existing)).thenReturn(existing);

        Address principal = service.makePrincipalAddress(USER_ID, "OFICINA");

        assertThat(principal.getAlias()).isEqualTo("oficina");
        assertThat(existing.getAddressList().getFirst().getAlias()).isEqualTo("oficina");
    }

    @Test
    void selectsOneAddressByItsAlias() throws FatumUserException {
        User existing = Fixtures.user();
        Address address = Fixtures.address(existing);
        existing.addAddress(address);
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(addressRepository.findByUserAwsIdAndAlias(USER_ID, "casa")).thenReturn(address);

        assertThat(service.selectAddress(USER_ID, "casa")).isSameAs(address);
    }

    @Test
    void deletesAnAddress() throws FatumUserException {
        User existing = Fixtures.user();
        Address address = Fixtures.address(existing);
        existing.addAddress(address);
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(addressRepository.findByUserAwsIdAndAlias(USER_ID, "casa")).thenReturn(address);
        when(userRepository.save(existing)).thenReturn(existing);

        service.removeAddress(USER_ID, "casa");

        assertThat(existing.getAddressList()).isEmpty();
    }

    @Test
    void replacesAnAddressAndKeepsItPrincipal() throws FatumUserException {
        User existing = Fixtures.user();
        Address current = persisted(Fixtures.address(existing), "addr-1");
        existing.addAddress(current);
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(addressRepository.findByUserAwsIdAndAlias(USER_ID, "casa")).thenReturn(current);
        when(addressRepository.existsByUserAwsIdAndAlias(USER_ID, "nueva")).thenReturn(false);
        when(userRepository.save(existing)).thenReturn(existing);

        Address replacement = service.updateAddress(USER_ID, "casa",
                new NewAddressRequest("calle 9", "nueva", "cali", "valle", "colombia"));

        assertThat(replacement.getAlias()).isEqualTo("nueva");
        assertThat(existing.getAddressList()).extracting(Address::getAlias).containsExactly("nueva");
    }

    @Test
    void readsTheAddressOfAProfessionalByUsername() throws FatumUserException {
        User professional = Fixtures.user();
        professional.addAddress(Fixtures.address(professional));
        professional.setRole(UserRole.PROFESSIONAL);
        when(userRepository.findByUsernameIgnoreCase(professional.getUsername())).thenReturn(professional);
        when(userRepository.findByAwsId(USER_ID)).thenReturn(professional);

        assertThat(service.getProfessionalAddress(professional.getUsername()).getAlias())
                .isEqualTo("casa");
    }

    @Test
    void anAccountThatIsNotAProfessionalHasNoProfessionalAddress() throws FatumUserException {
        User client = Fixtures.user();
        client.addAddress(Fixtures.address(client));
        when(userRepository.findByUsernameIgnoreCase(client.getUsername())).thenReturn(client);

        assertThatThrownBy(() -> service.getProfessionalAddress(client.getUsername()))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NO_PROFESSIONAL);
    }

    // ---------------------------------------------------------------------- misc

    @Test
    void deactivatesAnAccountAndAnnouncesIt() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        service.deactivateUser(existing.getEmail());

        assertThat(existing.isActive()).isFalse();
        verify(eventPublisherService)
                .activeStatusChanged(USER_ID, existing.getEmail(), false, UserRole.CLIENT);
    }

    @Test
    void activatesAnAccountAndAnnouncesItsRole() throws FatumUserException {
        User existing = Fixtures.user();
        existing.addAddress(Fixtures.address(existing));
        existing.setRole(UserRole.PROFESSIONAL);
        existing.deactivate();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        service.activateUser(existing.getEmail());

        assertThat(existing.isActive()).isTrue();
        verify(eventPublisherService)
                .activeStatusChanged(USER_ID, existing.getEmail(), true, UserRole.PROFESSIONAL);
    }

    // ------------------------------------------------------------- verification
    @Test
    void losingTheVerificationTakesTheProfessionalConditionAway() throws FatumUserException {
        User existing = Fixtures.user();
        existing.addAddress(Fixtures.address(existing));
        existing.setRole(UserRole.PROFESSIONAL);
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        User updated = service.changeVerificationStatus(USER_ID, VerificationStatus.REJECTED);

        assertThat(updated.getVerificationStatus()).isEqualTo(VerificationStatus.REJECTED);
        assertThat(updated.getRole()).isEqualTo(UserRole.CLIENT);
        verify(eventPublisherService)
                .verificationStatusChanged(USER_ID, existing.getEmail(), VerificationStatus.REJECTED);
        verify(eventPublisherService).professionalBecameClient(USER_ID, existing.getEmail());
    }

    @Test
    void aClientWhoLosesTheVerificationOnlyLeavesTheVerifiedGroup() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        service.changeVerificationStatus(USER_ID, VerificationStatus.UNVERIFIED);

        assertThat(existing.getRole()).isEqualTo(UserRole.CLIENT);
        verify(eventPublisherService)
                .verificationStatusChanged(USER_ID, existing.getEmail(), VerificationStatus.UNVERIFIED);
        verify(eventPublisherService, never()).professionalBecameClient(anyString(), anyString());
    }

    @Test
    void becomingVerifiedAgainDoesNotMakeAnybodyAProfessional() throws FatumUserException {
        User existing = Fixtures.user();
        existing.markVerificationStatus(VerificationStatus.REJECTED);
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        User updated = service.changeVerificationStatus(USER_ID, VerificationStatus.VERIFIED);

        assertThat(updated.getRole()).isEqualTo(UserRole.CLIENT);
        verify(eventPublisherService)
                .verificationStatusChanged(USER_ID, existing.getEmail(), VerificationStatus.VERIFIED);
        verify(eventPublisherService, never()).userBecameProfessional(anyString(), anyString());
    }

    @Test
    void recordingTheVerificationOfAnUnknownAccountFails() {
        when(userRepository.findByAwsId(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.changeVerificationStatus(USER_ID, VerificationStatus.REJECTED))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void theVerificationCannotBeRecordedAsNothing() {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThatThrownBy(() -> service.changeVerificationStatus(USER_ID, null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NULL_VALUE);
    }
}
