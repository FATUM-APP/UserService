package fatum.service;

import fatum.exception.FatumUserException;
import fatum.model.User;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;
import fatum.repository.UserRepository;
import fatum.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CognitoGroupService cognitoGroupService = mock(CognitoGroupService.class);

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, cognitoGroupService);
    }

    @Test
    void createsAUser() {
        User user = Fixtures.user();
        when(userRepository.save(user)).thenReturn(user);

        assertThat(service.createUser(user)).isEqualTo(user);
    }

    @Test
    void validatesAnAdultWithUniqueValues() throws Exception {
        User user = Fixtures.user();

        service.validateUser(user);

        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsAnUnderageUser() {
        User user = Fixtures.user(Fixtures.USER_ID, LocalDate.now().minusYears(17));

        assertThatThrownBy(() -> service.validateUser(user))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.UNDERAGE_USER);
    }

    @Test
    @DisplayName("A null user is a business error; a user without birth date cannot even be built")
    void rejectsAUserWithoutBirthDate() {
        assertThatThrownBy(() -> service.validateUser(null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.NULL_VALUE);
        assertThatThrownBy(() -> Fixtures.user(Fixtures.USER_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsARepeatedEmail() {
        User user = Fixtures.user();
        User other = Fixtures.userWithEmail("aws-user-2", user.getEmail());
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(other);

        assertThatThrownBy(() -> service.validateUser(user))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.EMAIL_EXISTS);
    }

    @Test
    void rejectsARepeatedUsernamePhoneNumberAndDocument() {
        User user = Fixtures.user();
        User other = Fixtures.user("aws-user-2");

        when(userRepository.findByUsernameIgnoreCase(user.getUsername())).thenReturn(other);
        assertThatThrownBy(() -> service.validateUser(user))
                .hasMessage(FatumUserException.USERNAME_EXISTS);

        org.mockito.Mockito.reset(userRepository);
        when(userRepository.findByPhoneNumber(user.getPhoneNumber())).thenReturn(other);
        assertThatThrownBy(() -> service.validateUser(user))
                .hasMessage(FatumUserException.PHONE_EXISTS);

        org.mockito.Mockito.reset(userRepository);
        when(userRepository.findByDocumentIgnoreCase(user.getDocument())).thenReturn(other);
        assertThatThrownBy(() -> service.validateUser(user))
                .hasMessage(FatumUserException.DOCUMENT_EXISTS);
    }

    @Test
    void rejectsAnAlreadyRegisteredSubject() {
        User user = Fixtures.user();
        when(userRepository.findByAwsId(user.getAwsId())).thenReturn(user);

        assertThatThrownBy(() -> service.validateUser(user))
                .hasMessage(FatumUserException.USER_ALREADY_EXISTS);
    }

    @Test
    void findsTheUserByEveryUniqueValue() throws Exception {
        User user = Fixtures.user();
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(userRepository.findByDocumentIgnoreCase(user.getDocument())).thenReturn(user);
        when(userRepository.findByUsernameIgnoreCase(user.getUsername())).thenReturn(user);
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(user);
        when(userRepository.findByPhoneNumber(user.getPhoneNumber())).thenReturn(user);

        assertThat(service.getUserById(Fixtures.USER_ID)).isEqualTo(user);
        assertThat(service.getUserByDocument(user.getDocument())).isEqualTo(user);
        assertThat(service.getUserByUsername(user.getUsername())).isEqualTo(user);
        assertThat(service.getUserByEmail(user.getEmail())).isEqualTo(user);
        assertThat(service.getUserByPhoneNumber(user.getPhoneNumber())).isEqualTo(user);
    }

    @Test
    void reportsWhenTheUserDoesNotExist() {
        assertThatThrownBy(() -> service.getUserById("ghost"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
        assertThatThrownBy(() -> service.getUserByDocument("ghost"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
        assertThatThrownBy(() -> service.getUserByEmail("ghost"))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USER_NOT_FOUND);
    }

    @Test
    void aBlankIdentifierIsNotLookedUp() {
        assertThat(service.userExistsById("   ")).isNull();
        assertThat(service.userExistsByEmail(null)).isNull();
        assertThat(service.userExistsByUsername("  ")).isNull();
        assertThat(service.userExistsByDocument(null)).isNull();
        assertThat(service.userExistsByPhoneNumber(" ")).isNull();
    }

    @Test
    void searchesByName() {
        when(userRepository.findByNameIgnoreCase("jane")).thenReturn(List.of(Fixtures.user()));

        assertThat(service.getUsersByName(" jane ")).hasSize(1);
    }

    @Test
    void updatesTheUsernameAndThePhoneNumber() throws Exception {
        User user = Fixtures.user();
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User updated = service.updateUser(Fixtures.USER_ID, "newusername", "+573009998877", null, null);

        assertThat(updated.getUsername()).isEqualTo("newusername");
        assertThat(updated.getPhoneNumber()).isEqualTo("+573009998877");
    }

    @Test
    void ignoresBlankValuesAndKeepsWhatIsStored() throws Exception {
        User user = Fixtures.user();
        String username = user.getUsername();
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User updated = service.updateUser(Fixtures.USER_ID, "  ", null, null, null);

        assertThat(updated.getUsername()).isEqualTo(username);
    }

    @Test
    void rejectsAUsernameTakenByAnotherUser() {
        User user = Fixtures.user();
        User other = Fixtures.user("aws-user-2");
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(userRepository.findByUsernameIgnoreCase("taken")).thenReturn(other);

        assertThatThrownBy(() -> service.updateUser(Fixtures.USER_ID, "taken", null, null, null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.USERNAME_EXISTS);
    }

    @Test
    void rejectsAPhoneNumberTakenByAnotherUser() {
        User user = Fixtures.user();
        User other = Fixtures.user("aws-user-2");
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(userRepository.findByPhoneNumber("+573001112222")).thenReturn(other);

        assertThatThrownBy(() -> service.updateUser(Fixtures.USER_ID, null, "+573001112222", null, null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PHONE_EXISTS);
    }

    @Test
    void aProfessionalNeedsACity() {
        User user = Fixtures.user();
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);

        assertThatThrownBy(() -> service.updateUser(Fixtures.USER_ID, null, null, UserRole.PROFESSIONAL, null))
                .isInstanceOf(FatumUserException.class)
                .hasMessage(FatumUserException.PROFESSIONAL_CITY);
    }

    @Test
    void becomingAProfessionalAddsTheUserToTheCognitoGroup() throws Exception {
        User user = Fixtures.user();
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User updated = service.updateUser(Fixtures.USER_ID, null, null, UserRole.PROFESSIONAL, "Medellin");

        assertThat(updated.getRole()).isEqualTo(UserRole.PROFESSIONAL);
        assertThat(updated.getCity()).isEqualTo("Medellin");
        verify(cognitoGroupService).grantProfessional(user.getUsername());
    }

    @Test
    void aClientUpdateDoesNotTouchTheCognitoGroups() throws Exception {
        User user = Fixtures.user();
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        service.updateUser(Fixtures.USER_ID, null, null, UserRole.CLIENT, null);

        verify(cognitoGroupService, never()).grantProfessional(anyString());
    }

    @Test
    void deactivatesTheUser() throws Exception {
        User user = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(user);

        service.deactivateUser(user.getEmail());

        assertThat(user.isActive()).isFalse();
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();
    }

    @Test
    void reportsTheVerificationStatus() throws Exception {
        User user = Fixtures.user();
        when(userRepository.findByAwsId(Fixtures.USER_ID)).thenReturn(user);

        assertThat(service.getVerificationStatus(Fixtures.USER_ID)).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(service.isUserVerified(Fixtures.USER_ID)).isFalse();

        user.markVerificationStatus(VerificationStatus.VERIFIED);

        assertThat(service.getVerificationStatus(Fixtures.USER_ID)).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(service.isUserVerified(Fixtures.USER_ID)).isTrue();
    }

    @Test
    void reportsWhetherAnAccountIsActive() throws Exception {
        User user = Fixtures.user();
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(user);

        assertThat(service.isUserActiveByEmail(user.getEmail())).isTrue();
    }
}
