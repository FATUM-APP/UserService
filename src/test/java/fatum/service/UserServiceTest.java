package fatum.service;

import fatum.dto.CreateUserRequest;
import fatum.dto.UserUpdateRequest;
import fatum.exception.FatumUserException;
import fatum.model.User;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;
import fatum.repository.AddressRepository;
import fatum.repository.UserRepository;
import fatum.service.cognito.CognitoGroupService;
import fatum.service.cognito.CognitoUserService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String USER_ID = Fixtures.USER_ID;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private CognitoGroupService cognitoGroupService;
    @Mock
    private CognitoUserService cognitoUserService;

    @InjectMocks
    private UserService service;

    // -------------------------------------------------------------------- create

    @Test
    void aNewAccountIsStoredAndJoinsTheVerifiedGroup() throws FatumUserException {
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User stored = service.createUser(Fixtures.createRequest());

        assertThat(stored.getAwsId()).isEqualTo(USER_ID);
        assertThat(stored.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(stored.getAddressList()).isEmpty();
        verify(cognitoGroupService).grantVerified(USER_ID);
    }

    @Test
    void aFailingGroupSyncIsReported() throws FatumUserException {
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        doThrow(new FatumUserException(FatumUserException.COGNITO_GROUP_FAILURE))
                .when(cognitoGroupService).grantVerified(USER_ID);

        assertThatThrownBy(() -> service.createUser(Fixtures.createRequest()))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.COGNITO_GROUP_FAILURE);
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
    void validatingAnAccountThatAlreadyExistsIsRejected() {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThatThrownBy(() -> service.validateUser(existing))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_ALREADY_EXISTS);
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
                new UserUpdateRequest("other", "+573000000001", null, null, null));

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
    void searchesByNameOverEveryAccount() {
        List<User> expected = List.of(Fixtures.user());
        when(userRepository.findByNameIgnoreCase("Jane Doe")).thenReturn(expected);

        assertThat(service.getUsersByName(" Jane Doe ")).isEqualTo(expected);
    }

    // ------------------------------------------------------- visibility by active state

    @Test
    void thePublicDirectoryOnlyListsActiveAccounts() {
        List<User> expected = List.of(Fixtures.user());
        when(userRepository.findByNameIgnoreCaseAndIsActive("Jane Doe", true)).thenReturn(expected);

        assertThat(service.getActiveUsersByName(" Jane Doe ")).isEqualTo(expected);
    }

    @Test
    void anActiveAccountIsVisibleToOtherUsers() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThat(service.getActiveUserById(USER_ID)).isSameAs(existing);
    }

    @Test
    void aDeactivatedAccountIsRefusedInsteadOfHidden() {
        User existing = Fixtures.user();
        existing.deactivate();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThatThrownBy(() -> service.getActiveUserById(USER_ID))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.INACTIVE);
    }

    @Test
    void anUnknownAccountIsStillNotFound() {
        when(userRepository.findByEmailIgnoreCase("ghost@fatum.com")).thenReturn(null);

        assertThatThrownBy(() -> service.getActiveUserByEmail("ghost@fatum.com"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void aDeactivatedAccountIsStillVisibleToTheBusinessRules() {
        User deactivated = Fixtures.user("aws-user-2");
        deactivated.deactivate();
        User candidate = Fixtures.userWithEmail("aws-user-3", deactivated.getEmail());
        when(userRepository.findByAwsId(candidate.getAwsId())).thenReturn(null);
        when(userRepository.findByEmailIgnoreCase(deactivated.getEmail())).thenReturn(deactivated);

        assertThatThrownBy(() -> service.validateUser(candidate))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.EMAIL_EXISTS);
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
                new UserUpdateRequest("newjane", "+573001112233", null, null, null));

        assertThat(updated.getUsername()).isEqualTo("newjane");
        assertThat(updated.getPhoneNumber()).isEqualTo("+573001112233");
        assertThat(updated.getRole()).isEqualTo(UserRole.CLIENT);
        verify(cognitoGroupService, never()).grantProfessional(USER_ID);
    }

    @Test
    void aUsernameAlreadyTakenIsRejected() {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.findByUsernameIgnoreCase("taken")).thenReturn(Fixtures.user("aws-user-2"));

        assertThatThrownBy(() -> service.updateUser(USER_ID,
                new UserUpdateRequest("taken", null, null, null, null)))
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
                new UserUpdateRequest(null, "+573009998877", null, null, null)))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PHONE_EXISTS);
    }

    @Test
    void keepingTheSameValuesChangesNothing() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        User updated = service.updateUser(USER_ID,
                new UserUpdateRequest(
                        existing.getUsername().toUpperCase(),
                        existing.getPhoneNumber(),
                        null,
                        null,
                        null));

        assertThat(updated.getUsername()).isEqualTo(existing.getUsername());
    }

    @Test
    void becomingAProfessionalAlsoJoinsTheProfessionalGroup() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        User updated = service.updateUser(USER_ID,
                new UserUpdateRequest(null, null, UserRole.PROFESSIONAL, null, Fixtures.addressRequest()));

        assertThat(updated.getRole()).isEqualTo(UserRole.PROFESSIONAL);
        assertThat(updated.getAddressList()).hasSize(1);
        assertThat(updated.getAddressList().getFirst().getCity()).isEqualTo("bogota");
        verify(cognitoGroupService).grantProfessional(USER_ID);
    }

    @Test
    void aProfessionalWithoutAnAddressIsRejected() {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);

        assertThatThrownBy(() -> service.updateUser(USER_ID,
                new UserUpdateRequest(null, null, UserRole.PROFESSIONAL, null, null)))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PROFESSIONAL_CITY);
    }

    @Test
    void aProfessionalKeepsTheAddressItAlreadyHad() throws FatumUserException {
        User existing = Fixtures.user();
        existing.addAddress(Fixtures.address(existing));
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        User updated = service.updateUser(USER_ID,
                new UserUpdateRequest(null, null, UserRole.PROFESSIONAL, null, null));

        assertThat(updated.getRole()).isEqualTo(UserRole.PROFESSIONAL);
        assertThat(updated.getAddressList()).hasSize(1);
    }

    @Test
    void updatingWithoutARoleKeepsTheCurrentOne() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByAwsId(USER_ID)).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        User updated = service.updateUser(USER_ID,
                new UserUpdateRequest(null, null, null, null, Fixtures.addressRequest()));

        assertThat(updated.getRole()).isEqualTo(UserRole.CLIENT);
        assertThat(updated.getAddressList()).hasSize(1);
    }

    // ---------------------------------------------------------------------- misc

    @Test
    void deactivatesAnAccount() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        service.deactivateUser(existing.getEmail());

        assertThat(existing.isActive()).isFalse();
        verify(userRepository).save(existing);
        verify(cognitoUserService).revokeAccess(USER_ID);
    }

    @Test
    void deactivatingAnAccountThatCognitoRefusesIsReportedWhenStrictModeIsOn() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);
        doThrow(new FatumUserException(FatumUserException.COGNITO_GROUP_FAILURE))
                .when(cognitoUserService).revokeAccess(USER_ID);

        assertThatThrownBy(() -> service.deactivateUser(existing.getEmail()))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.COGNITO_GROUP_FAILURE);
    }

    @Test
    void activatingAnAccountGivesTheAccessBack() throws FatumUserException {
        User existing = Fixtures.user();
        existing.deactivate();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        service.activateUser(existing.getEmail());

        assertThat(existing.isActive()).isTrue();
        verify(userRepository).save(existing);
        verify(cognitoUserService).enableUser(USER_ID);
        verify(cognitoGroupService).grantVerified(USER_ID);
    }

    @Test
    void activatingAProfessionalRestoresBothGroups() throws FatumUserException {
        User existing = Fixtures.user();
        existing.addAddress(Fixtures.address(existing));
        existing.setRole(UserRole.PROFESSIONAL);
        existing.deactivate();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);
        when(userRepository.save(existing)).thenReturn(existing);

        service.activateUser(existing.getEmail());

        verify(cognitoUserService).enableUser(USER_ID);
        verify(cognitoGroupService).grantVerified(USER_ID);
        verify(cognitoGroupService).grantProfessional(USER_ID);
    }

    @Test
    void reportsTheVerificationStatus() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);

        assertThat(service.getVerificationStatus(existing.getEmail()))
                .isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    void reportsWhetherAnAccountIsActive() throws FatumUserException {
        User existing = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(existing.getEmail())).thenReturn(existing);

        assertThat(service.isUserActiveByEmail(existing.getEmail())).isTrue();

        existing.deactivate();
        assertThat(service.isUserActiveByEmail(existing.getEmail())).isFalse();
    }
}
