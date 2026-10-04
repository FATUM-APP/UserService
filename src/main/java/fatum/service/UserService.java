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
import java.util.Optional;

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
    private User getUserByPhoneNumber(String phoneNumber) throws FatumUserException {
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

    /**
     * Lists the accounts with the given name that still have access.
     *
     * <p>This is what the public directory shows. The split matters: the queries the business rules
     * use (a duplicated email, a duplicated username) read the repository directly, so they keep
     * seeing deactivated accounts and cannot hand a taken value to a new sign-up.</p>
     */
    public List<User> getActiveUsersByName(String name) {
        return userRepository.findByNameIgnoreCaseAndIsActive(TextNormalizer.trimOrNull(name), true);
    }

    /**
     * Reads an account that any authenticated user may see.
     *
     * <p>The difference with {@link #getUserById(String)} is what happens with a deactivated
     * account: it is not hidden as if it did not exist, it is refused with {@code INACTIVE}, so the
     * caller knows the account is there but has no access. A missing one is still a
     * {@code USER_NOT_FOUND}.</p>
     *
     * @param awsId identifier of the account
     * @return the account, always active
     * @throws FatumUserException if the account does not exist or was deactivated
     */
    public User getActiveUserById(String awsId) throws FatumUserException {
        return requireActive(getUserById(awsId));
    }


    /** Reads an active account by its email. @see #getActiveUserById(String) */
    public User getActiveUserByEmail(String email) throws FatumUserException {
        return requireActive(getUserByEmail(email));
    }

    /** Reads an active account by its username. @see #getActiveUserById(String) */
    public User getActiveUserByUsername(String username) throws FatumUserException {
        return requireActive(getUserByUsername(username));
    }

    /** Refuses an account that was deactivated, whatever way it was found. */
    private User requireActive(User user) throws FatumUserException {
        if (!user.isActive()) {
            throw new FatumUserException(FatumUserException.INACTIVE);
        }
        return user;
    }

    public VerificationStatus getVerificationStatus(String email) throws FatumUserException {
        return getUserByEmail(email).getVerificationStatus();
    }

    public List<User> getProfessionals() {
        return getUsersByRole(UserRole.PROFESSIONAL);
    }
    private List<User> getUsersByRole(UserRole role) {
        return userRepository.findByRoleAndIsActive(role, true);
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

     //================================================================================================================
     //                                              ADDRESS
     //===============================================================================================================


    /**
     * Returns the principal address of the account, that is, the first one of the list.
     *
     * @param awsId identifier of the account
     * @return the principal address
     * @throws FatumUserException if the user does not exist or has no address
     */
    @Transactional(readOnly = true)
    public Address getPrincipalAddress(String awsId) throws FatumUserException {
        User user = getUserById(awsId);
        if (!user.hasAddress()) {
            throw new FatumUserException(FatumUserException.ADDRESS_NOT_FOUND);
        }
        return user.getPrincipalAddress();
    }

    /**
     * Moves one address to the head of the list, which is what makes it the principal one.
     *
     * @param awsId identifier of the account
     * @param alias name that identifies the address inside the account
     * @return the address that is principal now
     * @throws FatumUserException if the user or the address does not exist
     */
    @Transactional
    public Address makePrincipalAddress(String awsId, String alias) throws FatumUserException {
        User user = getUserById(awsId);
        Address address = findAddress(user, alias);
        user.makePrincipalAddress(address);
        userRepository.save(user);
        return address;
    }

    /**
     * Adds an address to the account.
     *
     * <p>The pair (aws identifier, alias) is unique in the table, so the alias is checked before the
     * address reaches the list: a constraint violation would tell the caller nothing about what went
     * wrong.</p>
     *
     * @param awsId   identifier of the account
     * @param request address to store
     * @return the stored address
     * @throws FatumUserException if the user does not exist or already has an address with that alias
     */
    @Transactional
    public Address addAddress(String awsId, NewAddressRequest request) throws FatumUserException {
        User user = getUserById(awsId);
        Address address = toNewAddress(request, user);
        ensureAliasIsFree(user, address.getAlias());
        user.addAddress(address);
        addressRepository.save(address);
        userRepository.save(user);
        return address;
    }

    /**
     * Lists the addresses of the account. The first one is the principal address.
     *
     * @param awsId identifier of the account
     * @return the addresses, in order
     * @throws FatumUserException if the user does not exist
     */
    @Transactional(readOnly = true)
    public List<Address> getAddresses(String awsId) throws FatumUserException {
        return List.copyOf(getUserById(awsId).getAddressList());
    }

    /**
     * Finds one address of the account by its alias.
     *
     * @param awsId identifier of the account
     * @param alias name that identifies the address inside the account
     * @return the stored address
     * @throws FatumUserException if the user or the address does not exist
     */
    @Transactional(readOnly = true)
    public Address getAddress(String awsId, String alias) throws FatumUserException {
        return findAddress(getUserById(awsId), alias);
    }

    /**
     * The address of a professional, looked up by the username.
     *
     * <p>It is the only address read by username, and it cannot be otherwise: the caller is another
     * account, which knows the username it saw in a listing and never the aws identifier.</p>
     *
     * @param username username of the professional
     * @return the address that represents the professional
     * @throws FatumUserException if the account does not exist, is not active or is not a professional
     */
    @Transactional(readOnly = true)
    public Address getProfessionalAddress(String username) throws FatumUserException {
        User professional = getActiveUserByUsername(username);
        if (professional.getRole() != UserRole.PROFESSIONAL) {
            throw new FatumUserException(FatumUserException.NO_PROFESSIONAL);
        }
        return getPrincipalAddress(professional.getAwsId());
    }
    /**
     * Replaces one address of the account with another one.
     *
     * <p>The entity has no setters, so updating is adding the new address and removing the old one,
     * and the order is not cosmetic: {@code removeAddress} refuses to leave a professional account
     * without an address, so adding first keeps that rule from firing on a replacement. When the
     * replaced address was the principal one, the new one takes its place at the head of the list.</p>
     *
     * @param awsId   identifier of the account
     * @param alias   alias that identifies the address to replace
     * @param request new values of the address
     * @return the stored address
     * @throws FatumUserException if the user or the address does not exist, or the new alias is
     *                            already used by another address of the same account
     */
    @Transactional
    public Address updateAddress(String awsId, String alias, NewAddressRequest request)
            throws FatumUserException {
        User user = getUserById(awsId);
        Address current = findAddress(user, alias);
        Address replacement = toNewAddress(request, user);
        if (!replacement.getAlias().equals(current.getAlias())) {
            ensureAliasIsFree(user, replacement.getAlias());
        }
        boolean wasPrincipal = user.getPrincipalAddress().getAlias().equals(current.getAlias());
        if (wasPrincipal) {
            user.makePrincipalAddress(replacement);
        } else {
            user.addAddress(replacement);
        }
        user.removeAddress(current);
        addressRepository.save(replacement);
        userRepository.save(user);
        return replacement;
    }

    /**
     * Deletes one address of the account.
     *
     * <p>The rule that protects a professional account is not repeated here: the entity owns it and
     * {@code removeAddress} rejects the deletion of the last address. The row disappears with the
     * collection, because the association is declared with {@code orphanRemoval}.</p>
     *
     * @param awsId identifier of the account
     * @param alias alias that identifies the address
     * @throws FatumUserException if the user or the address does not exist, or it is the last address
     *                            of a professional account
     */
    @Transactional
    public void removeAddress(String awsId, String alias) throws FatumUserException {
        User user = getUserById(awsId);
        user.removeAddress(findAddress(user, alias));
        userRepository.save(user);
    }

    /**
     * Finds an address of the account by its alias.
     *
     * <p>The lookup goes to the table instead of scanning the loaded collection: the pair (aws
     * identifier, alias) is what the unique index covers, and that is also what guarantees the query
     * returns one row instead of two.</p>
     */
    private Address findAddress(User user, String alias) throws FatumUserException {
        requireActive(user);
        Address address = addressRepository.findByUserAwsIdAndAlias(user.getAwsId(), alias);
        if(address == null) throw new FatumUserException(FatumUserException.ADDRESS_NOT_FOUND);
        return address;
    }

    /** Rejects an alias the account is already using. */
    private void ensureAliasIsFree(User user, String alias) throws FatumUserException {
        if (addressRepository.existsByUserAwsIdAndAlias(user.getAwsId(), alias)) {
            throw new FatumUserException(FatumUserException.ADDRESS_EXISTS);
        }
    }

    /** Builds the entity, and refuses a request that carries no address. */
    private Address toNewAddress(NewAddressRequest request, User user) throws FatumUserException {
        Address address = AddressMapper.toEntity(request, user);
        if (address == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        return address;
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

    /**
     * Gives the account back the access a deactivation took away.
     *
     * <p>It mirrors {@link #deactivateUser(String)}: the database goes first and the user pool
     * after. The groups are restored as well, because revoking the access empties them, and an
     * account without its group loses the permissions the rest of the platform reads from the
     * token.</p>
     *
     * @param email email of the account
     * @throws FatumUserException if the account does not exist
     */
    @Transactional
    public void activateUser(String email) throws FatumUserException {
        User user = getUserByEmail(email);
        user.activate();
        User saved = userRepository.save(user);
        cognitoUserService.enableUser(saved.getAwsId());
        cognitoGroupService.grantVerified(saved.getAwsId());
        if (saved.getRole() == UserRole.PROFESSIONAL) {
            cognitoGroupService.grantProfessional(saved.getAwsId());
        }
    }

}
