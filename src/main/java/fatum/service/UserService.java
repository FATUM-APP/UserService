package fatum.service;

import fatum.dto.NewAddressRequest;
import fatum.dto.TextNormalizer;
import fatum.dto.UserUpdateRequest;
import fatum.exception.FatumUserException;
import fatum.dto.mapper.AddressMapper;
import fatum.model.Address;
import fatum.model.User;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;
import fatum.repository.UserRepository;
import fatum.service.cognito.CognitoGroupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final CognitoGroupService cognitoGroupService;

    public UserService(UserRepository userRepository,
                       CognitoGroupService cognitoGroupService
    ) {
        this.userRepository = userRepository;
        this.cognitoGroupService = cognitoGroupService;
    }

    /**
     * Stores a new account.
     *
     * <p>Every account is born VERIFIED, so it joins the verified group of the user pool at the same
     * moment it is created. The document pipeline is not running for now, which is why there is no
     * verification step in between.</p>
     */
    @Transactional
    public User createUser(User newUser) throws FatumUserException {
        User saved = userRepository.save(newUser);
        cognitoGroupService.grantVerified(saved.getAwsId());
        return saved;
    }

    public void validateUser(User user) throws FatumUserException {
        validateNewUser(user);
        validateUniqueValues(user);
    }


    public User getUserById(String awsId) throws FatumUserException {
        User user = userRepository.findByAwsId(TextNormalizer.trimOrNull(awsId));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User userExistsById(String awsId) {
        return userRepository.findByAwsId(TextNormalizer.trimOrNull(awsId));
    }


    public User getUserByDocument(String document) throws FatumUserException {
        User user = userRepository.findByDocumentIgnoreCase(TextNormalizer.trimOrNull(document));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User userExistsByDocument(String document) {
        return userRepository.findByDocumentIgnoreCase(TextNormalizer.trimOrNull(document));
    }


    public User getUserByUsername(String username) throws FatumUserException {
             User user = userRepository.findByUsernameIgnoreCase(TextNormalizer.trimOrNull( username));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User userExistsByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(TextNormalizer.trimOrNull( username));
    }

    public User getUserByEmail(String email) throws FatumUserException {
        User user = userRepository.findByEmailIgnoreCase(TextNormalizer.trimOrNull(email));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User userExistsByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(TextNormalizer.trimOrNull(email));
    }


    public User getUserByPhoneNumber(String phoneNumber) throws FatumUserException {
        User user = userRepository.findByPhoneNumber(TextNormalizer.trimOrNull(phoneNumber));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User userExistsByPhoneNumber(String phoneNumber) {
        return userRepository.findByPhoneNumber(TextNormalizer.trimOrNull(phoneNumber));
    }


    public List<User> getUsersByName(String name) {
        return userRepository.findByNameIgnoreCase(TextNormalizer.trimOrNull(name));
    }

    /**
     * Applies the editable fields of an account and keeps the user pool in sync.
     *
     * <p>Becoming a professional also adds the account to the professional group, which is the
     * contract the rest of the platform reads. The call is idempotent in Cognito.</p>
     */
    @Transactional
    public User updateUser(
            String awsId,
            UserUpdateRequest request
            ) throws FatumUserException {

        User existingUser = getUserById(awsId);
        updateUsername(existingUser, request.username());
        updatePhoneNumber(existingUser, request.phoneNumber());
        updateRoleAndAddress(existingUser, request.role(), request.newAddress());
        User saved = userRepository.save(existingUser);
        if (saved.getRole() == UserRole.PROFESSIONAL) {
            cognitoGroupService.grantProfessional(saved.getAwsId());
        }
        return saved;
    }

    @Transactional
    public void deactivateUser(String email) throws FatumUserException {
        User user = getUserByEmail(email);
        user.deactivate();
        userRepository.save(user);
    }

    public VerificationStatus getVerificationStatus(String awsId) throws FatumUserException {
        return getUserById(awsId).getVerificationStatus();
    }

    public boolean isUserActiveByEmail(String email) throws FatumUserException {
        return getUserByEmail(email).isActive();
    }

    private void updateUsername(User user, String requestedUsername) throws FatumUserException {
        String username = TextNormalizer.trimOrNull(requestedUsername);
        if (username == null || username.equalsIgnoreCase(user.getUsername())) {
            return;
        }
        validateUniqueUsername(username, user.getAwsId());
        user.setUsername(username);
    }

    private void updatePhoneNumber(User user, String requestedPhoneNumber)
            throws FatumUserException {
        String phoneNumber = TextNormalizer.trimOrNull(requestedPhoneNumber);
        if (phoneNumber == null || phoneNumber.equals(user.getPhoneNumber())) {
            return;
        }
        validateUniquePhoneNumber(phoneNumber, user.getAwsId());
        user.setPhoneNumber(phoneNumber);
    }

    /**
     * Applies the address that was sent and, when a role was sent, the new role.
     *
     * <p>The rule "a professional needs an address" is not repeated here: the entity owns it and
     * rejects the change with {@code PROFESSIONAL_CITY} when the account has none.</p>
     */
    private void updateRoleAndAddress(User user, UserRole role, NewAddressRequest requestedAddress)
            throws FatumUserException {
        Address newAddress = AddressMapper.toEntity(requestedAddress, user);
        if (newAddress != null && !user.addressInList(newAddress)) {
            user.addAddress(newAddress);
        }
        if (role != null) {
            user.setRole(role);
        }
    }

    private void validateNewUser(User user) throws FatumUserException {
        if (user == null || user.getBirthDate() == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        if (user.getBirthDate().isAfter(LocalDate.now().minusYears(18))) {
            throw new FatumUserException(FatumUserException.UNDERAGE_USER);
        }
    }

    private void validateUniqueValues(User user) throws FatumUserException {
        validateUniqueAwsId(user.getAwsId());
        validateUniqueEmail(user.getEmail(), user.getAwsId());
        validateUniquePhoneNumber(user.getPhoneNumber(), user.getAwsId());
        validateUniqueUsername(user.getUsername(), user.getAwsId());
        validateUniqueDocument(user.getDocument(), user.getAwsId());
    }

    private void validateUniqueEmail(String email, String excludeawsId) throws FatumUserException {
        User conflict = userExistsByEmail(email);
        if (conflict != null && !conflict.getAwsId().equals(excludeawsId)) {
            throw new FatumUserException(FatumUserException.EMAIL_EXISTS);
        }
    }

    private void validateUniqueUsername(String username, String excludeawsId) throws FatumUserException {
        User conflict = userExistsByUsername(username);
        if (conflict != null && !conflict.getAwsId().equals(excludeawsId)) {
            throw new FatumUserException(FatumUserException.USERNAME_EXISTS);
        }
    }

    private void validateUniquePhoneNumber(String phoneNumber, String excludeawsId) throws FatumUserException {
        User conflict = userExistsByPhoneNumber(phoneNumber);
        if (conflict != null && !conflict.getAwsId().equals(excludeawsId)) {
            throw new FatumUserException(FatumUserException.PHONE_EXISTS);
        }
    }

    private void validateUniqueDocument(String document, String excludeawsId) throws FatumUserException {
        User conflict = userExistsByDocument(document);
        if (conflict != null && !conflict.getAwsId().equals(excludeawsId)) {
            throw new FatumUserException(FatumUserException.DOCUMENT_EXISTS);
        }
    }

    private void validateUniqueAwsId(String awsId) throws FatumUserException {
        if (userExistsById(awsId) != null) {
            throw new FatumUserException(FatumUserException.USER_ALREADY_EXISTS);
        }
    }

}
