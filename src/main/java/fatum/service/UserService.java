package fatum.service;

import fatum.dto.CreateUserRequest;
import fatum.dto.NewAddressRequest;
import fatum.dto.TextNormalizer;
import fatum.dto.UserUpdateRequest;
import fatum.dto.mapper.UserMapper;
import fatum.exception.FatumUserException;
import fatum.dto.mapper.AddressMapper;
import fatum.model.Address;
import fatum.model.User;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;
import fatum.repository.AddressRepository;
import fatum.repository.UserRepository;
import fatum.service.cognito.CognitoGroupService;
import fatum.service.cognito.CognitoUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.cognitoidentityprovider.endpoints.internal.Value;

import java.time.LocalDate;
import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final CognitoGroupService cognitoGroupService;
    private final CognitoUserService cognitoUserService;
    private final AddressRepository addressRepository;

    public UserService(UserRepository userRepository,
                       CognitoGroupService cognitoGroupService,
                       CognitoUserService cognitoUserService,
                       AddressRepository addressRepository
    ) {
        this.userRepository = userRepository;
        this.cognitoGroupService = cognitoGroupService;
        this.cognitoUserService = cognitoUserService;
        this.addressRepository = addressRepository;

    }

     //================================================================================================================
                                                  //  CREATE
     //===============================================================================================================


    /**
     * Creates a new user and adds it to the database.
     * @param newUserRequest DTO with the user's data
     * @return User object with the new user's data
     * @throws FatumUserException
     */
    @Transactional
    public User createUser(CreateUserRequest newUserRequest) throws FatumUserException {
        User newUser = UserMapper.toEntity(newUserRequest);
        User saved = userRepository.save(newUser);
        cognitoGroupService.grantVerified(saved.getAwsId());
        return saved;
    }

    /**
     * Validates the user data before creating it.
     * @param user
     * @throws FatumUserException
     */
    public void validateUser(User user) throws FatumUserException {
        validateNewUser(user);
        validateUniqueValues(user);
    }


     //================================================================================================================
      //                                              READ
     //================================================================================================================



    /**
     * Get a user by its id.
     * @param awsId
     * @return user or null
     * @throws FatumUserException if the user is not found
     */
    public User getUserById(String awsId) throws FatumUserException {
        User user = userRepository.findByAwsId(TextNormalizer.trimOrNull(awsId));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * Check if a user exists by its id.
     * @param awsId
     * @return boolean
     */
    private boolean userExistsById(String awsId) {
        return userRepository.findByAwsId(TextNormalizer.trimOrNull(awsId)) != null;
    }


    /**
     * Get a user by its document.
     * @param document
     * @return user or null
     * @throws FatumUserException if the user is not found
     */
    private User getUserByDocument(String document) throws FatumUserException {
        User user = userRepository.findByDocumentIgnoreCase(TextNormalizer.trimOrNull(document));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * Check if a user exists by its document.
     * @param document
     * @return boolean
     */
    private boolean userExistsByDocument(String document) {
        return userRepository.findByDocumentIgnoreCase(TextNormalizer.trimOrNull(document)) != null;
    }

    /**
     * Get a user by its username.
     * @param username
     * @return user or null
     * @throws FatumUserException  if the user is not found
     */
    public User getUserByUsername(String username) throws FatumUserException {
             User user = userRepository.findByUsernameIgnoreCase(TextNormalizer.trimOrNull( username));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * Check if a user exists by its username.
     * @param username
     * @return boolean
     */
    private boolean userExistsByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(TextNormalizer.trimOrNull( username)) != null;
    }

    /**
     * Get a user by its email.
     * @param email
     * @return user or null
     * @throws FatumUserException if the user is not found
     */
    public User getUserByEmail(String email) throws FatumUserException {
        User user = userRepository.findByEmailIgnoreCase(TextNormalizer.trimOrNull(email));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * Check if a user exists by its email.
     * @param email
     * @return boolean
     */
    private Boolean userExistsByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(TextNormalizer.trimOrNull(email)) != null;
    }

    /**
     * Get a user by its phone number.
     * @param phoneNumber
     * @return user or null
     * @throws FatumUserException if user is not found
     */
    public User getUserByPhoneNumber(String phoneNumber) throws FatumUserException {
        User user = userRepository.findByPhoneNumber(TextNormalizer.trimOrNull(phoneNumber));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    /**
     * Check if a user exists by its phone number.
     * @param phoneNumber
     * @return boolean
     */
    private Boolean userExistsByPhoneNumber(String phoneNumber) {
        return userRepository.findByPhoneNumber(TextNormalizer.trimOrNull(phoneNumber)) != null;
    }

    /**
     * Get all users with the given name.
     * @param name
     * @return list of users
     */
    public List<User> getUsersByName(String name) {
        return userRepository.findByNameIgnoreCase(TextNormalizer.trimOrNull(name));
    }

    public VerificationStatus getVerificationStatus(String awsId) throws FatumUserException {
        return getUserById(awsId).getVerificationStatus();
    }

    public boolean isUserActiveByEmail(String email) throws FatumUserException {
        return getUserByEmail(email).isActive();
    }


     //==============================================================================================================
     //                                               UPDATE
     //=============================================================================================================


    /**
     * Updates the user's data.
     * @param awsId
     * @param request
     * @return
     * @throws FatumUserException
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
        User conflict = userExistsByEmail(email) ? getUserByEmail(email) : null;
        if (conflict != null && !conflict.getAwsId().equals(excludeawsId)) {
            throw new FatumUserException(FatumUserException.EMAIL_EXISTS);
        }
    }

    private void validateUniqueUsername(String username, String excludeawsId) throws FatumUserException {
        User conflict = userExistsByUsername(username) ? getUserByUsername(username) : null;
        if (conflict != null && !conflict.getAwsId().equals(excludeawsId)) {
            throw new FatumUserException(FatumUserException.USERNAME_EXISTS);
        }
    }

    private void validateUniquePhoneNumber(String phoneNumber, String excludeawsId) throws FatumUserException {
        User conflict = userExistsByPhoneNumber(phoneNumber) ? getUserByPhoneNumber(phoneNumber) : null;
        if (conflict != null && !conflict.getAwsId().equals(excludeawsId)) {
            throw new FatumUserException(FatumUserException.PHONE_EXISTS);
        }
    }

    private void validateUniqueDocument(String document, String excludeawsId) throws FatumUserException {
        User conflict = userExistsByDocument(document) ? getUserByDocument(document) : null;
        if (conflict != null && !conflict.getAwsId().equals(excludeawsId)) {
            throw new FatumUserException(FatumUserException.DOCUMENT_EXISTS);
        }
    }

    private void validateUniqueAwsId(String awsId) throws FatumUserException {
        if (userExistsById(awsId)) {
            throw new FatumUserException(FatumUserException.USER_ALREADY_EXISTS);
        }
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
            addressRepository.save(newAddress);
        }
        if (role != null) {
            user.setRole(role);
        }
    }

    public Address addAddress(String awsId, NewAddressRequest newAddress) throws FatumUserException {
        User user = getUserById(awsId);
        Address address = AddressMapper.toEntity(newAddress, user);
        user.addAddress(address);
        Address saved = addressRepository.save(address);
        userRepository.save(user);
        return saved;

    }

    public Address removeAddress(String awsId, NewAddressRequest newAddress) throws FatumUserException {
        User user = getUserById(awsId);
        Address address = AddressMapper.toEntity(newAddress, user);
        user.addAddress(address);
        Address saved = addressRepository.save(address);
        userRepository.save(user);
        return saved;

    }

    /**
     * Deactivates the account and takes away every access it had.
     *
     * <p>The database is written first and the user pool follows, the same order the other
     * operations use: the local state is the source of truth, and a Cognito outage is logged instead
     * of blocking the deactivation (unless strict mode is enabled).</p>
     */
    @Transactional
    public void deactivateUser(String email) throws FatumUserException {
        User user = getUserByEmail(email);
        user.deactivate();
        User saved = userRepository.save(user);
        cognitoUserService.revokeAccess(saved.getAwsId());
    }

}
