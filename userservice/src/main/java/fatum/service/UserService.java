package fatum.service;

import fatum.dto.CreateUserRequest;
import fatum.common.dto.user.M2User;
import fatum.dto.NewAddressRequest;
import fatum.dto.TextNormalizer;
import fatum.dto.UserResponse;
import fatum.dto.UserUpdateRequest;
import fatum.dto.mapper.UserMapper;
import fatum.exception.FatumUserException;
import fatum.dto.mapper.AddressMapper;
import fatum.model.Address;
import fatum.model.User;
import fatum.common.enums.user.UserRole;
import fatum.common.enums.user.VerificationStatus;
import fatum.repository.AddressRepository;
import fatum.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final EventPublisherService eventPublisherService;

    public UserService(UserRepository userRepository,
                       AddressRepository addressRepository,
                       EventPublisherService eventPublisherService
    ) {
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
        this.eventPublisherService = eventPublisherService;
    }

    //================================================================================================================
    //                                                CREATE
    //================================================================================================================

    @Transactional
    public User createUser(CreateUserRequest newUserRequest) throws FatumUserException {
        User newUser = UserMapper.toEntity(newUserRequest);
        User saved = userRepository.save(newUser);
        eventPublisherService.verificationStatusChanged(saved.getUserId(), saved.getEmail(), saved.getVerificationStatus());
        return saved;
    }

    public void validateUser(User user) throws FatumUserException {
        validateNewUser(user);
        validateUniqueValues(user);
    }

    //================================================================================================================
    //                                                READ
    //================================================================================================================

    @Transactional(readOnly = true)
    public UserResponse getUserResponseById(String awsId) throws FatumUserException {
        return UserMapper.toResponse(getUserById(awsId));
    }

    @Transactional(readOnly = true)
    public M2User getM2UserByUserId(String userId) throws FatumUserException {
        return UserMapper.toM2Response(getUserByUserId(userId));
    }

    @Transactional(readOnly = true)
    public UserResponse getActiveUserResponseByUsername(String username) throws FatumUserException {
        return UserMapper.toResponse(getActiveUserByUsername(username));
    }

    @Transactional(readOnly = true)
    public UserResponse getActiveUserResponseByEmail(String email) throws FatumUserException {
        return UserMapper.toResponse(getActiveUserByEmail(email));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getActiveUserResponsesByName(String name) {
        return UserMapper.toResponseList(getActiveUsersByName(name));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getProfessionalResponses() {
        return UserMapper.toResponseList(getProfessionals());
    }

    @Transactional(readOnly = true)
    public UserResponse getUserResponseByEmail(String email) throws FatumUserException {
        return UserMapper.toResponse(getUserByEmail(email));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getUserResponsesByName(String name) {
        return UserMapper.toResponseList(getUsersByName(name));
    }

    public VerificationStatus getVerificationStatus(String email) throws FatumUserException {
        return getUserByEmail(email).getVerificationStatus();
    }

    public boolean isUserActiveByEmail(String email) throws FatumUserException {
        return getUserByEmail(email).isActive();
    }

    //================================================================================================================
    //                                               UPDATE
    //================================================================================================================

    @Transactional
    public UserResponse updateUserResponse(String awsId, UserUpdateRequest request) throws FatumUserException {
        return UserMapper.toResponse(updateUser(awsId, request));
    }

    @Transactional
    public User updateUser(String awsId, UserUpdateRequest request) throws FatumUserException {
        User existingUser = getUserById(awsId);
        updateUsername(existingUser, request.username());
        updatePhoneNumber(existingUser, request.phoneNumber());
        if (request.newAddress() != null) {
            addAddress(existingUser.getAwsId(), request.newAddress());
        }
        User saved = userRepository.save(existingUser);
        return saved;
    }

    @Transactional
    public void becomeProfessionalWithAddress(String awsId, NewAddressRequest requestedAddress)
            throws FatumUserException {
        Address address = addAddress(awsId, requestedAddress);
        User user = getUserById(awsId);
        user.makePrincipalAddress(address);
        userRepository.save(user);
        setUserRole(awsId, UserRole.PROFESSIONAL);
        eventPublisherService.professionalPrincipalAddressChanged(user.getUserId(), user.getEmail(), address.getAlias());
    }

    public void becomeProfessional(String awsId) throws FatumUserException {
        setUserRole(awsId, UserRole.PROFESSIONAL);
    }

    public void becomeClient(String awsId) throws FatumUserException {
        setUserRole(awsId, UserRole.CLIENT);
    }

    //================================================================================================================
    //                                            VERIFICATION
    //================================================================================================================

    @Transactional
    public User changeVerificationStatus(String awsId, VerificationStatus status) throws FatumUserException {
        User user = getUserById(awsId);
        UserRole previousRole = user.getRole();
        user.markVerificationStatus(status);
        userRepository.save(user);
        eventPublisherService.verificationStatusChanged(user.getUserId(), user.getEmail(), user.getVerificationStatus());
        if (previousRole == UserRole.PROFESSIONAL && user.getRole() == UserRole.CLIENT) {
            eventPublisherService.professionalBecameClient(user.getUserId(), user.getEmail());
        }
        return user;
    }

    @Transactional
    public void deactivateUser(String email) throws FatumUserException {
        User user = getUserByEmail(email);
        user.deactivate();
        User saved = userRepository.save(user);
        eventPublisherService.activeStatusChanged(saved.getUserId(), email, false, saved.getRole());
    }

    @Transactional
    public void activateUser(String email) throws FatumUserException {
        User user = getUserByEmail(email);
        user.activate();
        User saved = userRepository.save(user);
        eventPublisherService.activeStatusChanged(saved.getUserId(), email, true, saved.getRole());
    }

    //================================================================================================================
    //                                              ADDRESS
    //================================================================================================================

    @Transactional(readOnly = true)
    public Address getPrincipalAddress(String awsId) throws FatumUserException {
        User user = getUserById(awsId);
        if (!user.hasAddress()) {
            throw new FatumUserException(FatumUserException.ADDRESS_NOT_FOUND);
        }
        return user.getPrincipalAddress();
    }

    @Transactional
    public Address makePrincipalAddress(String awsId, String alias) throws FatumUserException {
        User user = getUserById(awsId);
        Address address = findAddress(user, alias);
        user.makePrincipalAddress(address);
        userRepository.save(user);
        return address;
    }

    @Transactional
    public Address addAddress(String awsId, NewAddressRequest request) throws FatumUserException {
        User user = getUserById(awsId);
        Address address = toNewAddress(request, user);
        if (address == null) throw new FatumUserException(FatumUserException.NULL_VALUE);
        ensureAliasIsFree(user, address.getAlias());
        if (!user.addressInList(address)) {
            user.addAddress(address);
            addressRepository.save(address);
            userRepository.save(user);
        }
        return address;
    }

    @Transactional(readOnly = true)
    public List<Address> getAddresses(String awsId) throws FatumUserException {
        return List.copyOf(getUserById(awsId).getAddressList());
    }

    @Transactional(readOnly = true)
    public Address getAddress(String awsId, String alias) throws FatumUserException {
        return findAddress(getUserById(awsId), alias);
    }

    @Transactional(readOnly = true)
    public Address getProfessionalAddress(String username) throws FatumUserException {
        User professional = getActiveUserByUsername(username);
        if (professional.getRole() != UserRole.PROFESSIONAL) {
            throw new FatumUserException(FatumUserException.NO_PROFESSIONAL);
        }
        return getPrincipalAddress(professional.getAwsId());
    }

    public Address selectAddress(String awsId, String alias) throws FatumUserException {
        User user = getUserById(awsId);
        return findAddress(user, alias);
    }

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

    @Transactional
    public void removeAddress(String awsId, String alias) throws FatumUserException {
        User user = getUserById(awsId);
        user.removeAddress(findAddress(user, alias));
        userRepository.save(user);
    }

    //================================================================================================================
    //                                              HELPERS
    //================================================================================================================

    private User getUserById(String awsId) throws FatumUserException {
        User user = userRepository.findByAwsId(TextNormalizer.trimOrNull(awsId));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    private User getUserByUserId(String userId) throws FatumUserException {
        User user = userRepository.findByUserId(TextNormalizer.trimOrNull(userId));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    private User getUserByUsername(String username) throws FatumUserException {
        User user = userRepository.findByUsernameIgnoreCase(TextNormalizer.trimOrNull(username));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    private User getUserByEmail(String email) throws FatumUserException {
        User user = userRepository.findByEmailIgnoreCase(TextNormalizer.trimOrNull(email));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    private User getUserByDocument(String document) throws FatumUserException {
        User user = userRepository.findByDocumentIgnoreCase(TextNormalizer.trimOrNull(document));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    private User getUserByPhoneNumber(String phoneNumber) throws FatumUserException {
        User user = userRepository.findByPhoneNumber(TextNormalizer.trimOrNull(phoneNumber));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    private List<User> getUsersByName(String name) {
        return userRepository.findByNameIgnoreCase(TextNormalizer.trimOrNull(name));
    }

    private List<User> getActiveUsersByName(String name) {
        return userRepository.findByNameIgnoreCaseAndIsActive(TextNormalizer.trimOrNull(name), true);
    }

    private User getActiveUserById(String awsId) throws FatumUserException {
        return requireActive(getUserById(awsId));
    }

    private User getActiveUserByEmail(String email) throws FatumUserException {
        return requireActive(getUserByEmail(email));
    }

    private User getActiveUserByUsername(String username) throws FatumUserException {
        return requireActive(getUserByUsername(username));
    }

    private User requireActive(User user) throws FatumUserException {
        if (!user.isActive()) {
            throw new FatumUserException(FatumUserException.INACTIVE);
        }
        return user;
    }

    private List<User> getProfessionals() {
        return userRepository.findByRoleAndIsActive(UserRole.PROFESSIONAL, true);
    }

    private boolean userExistsById(String awsId) {
        return userRepository.findByAwsId(TextNormalizer.trimOrNull(awsId)) != null;
    }

    private boolean userExistsByDocument(String document) {
        return userRepository.findByDocumentIgnoreCase(TextNormalizer.trimOrNull(document)) != null;
    }

    private boolean userExistsByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(TextNormalizer.trimOrNull(username)) != null;
    }

    private Boolean userExistsByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(TextNormalizer.trimOrNull(email)) != null;
    }

    private Boolean userExistsByPhoneNumber(String phoneNumber) {
        return userRepository.findByPhoneNumber(TextNormalizer.trimOrNull(phoneNumber)) != null;
    }

    private void setUserRole(String awsId, UserRole role) throws FatumUserException {
        User user = getUserById(awsId);
        if (role.equals(UserRole.PROFESSIONAL) && !user.getVerificationStatus().equals(VerificationStatus.VERIFIED))
            throw new FatumUserException(FatumUserException.FORBIDDEN);
        if (!user.hasAddress() && role == UserRole.PROFESSIONAL)
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        UserRole previousRole = user.getRole();
        user.setRole(role);
        userRepository.save(user);
        if (role == UserRole.PROFESSIONAL) {
            eventPublisherService.userBecameProfessional(user.getUserId(), user.getEmail());
        } else if (previousRole == UserRole.PROFESSIONAL) {
            eventPublisherService.professionalBecameClient(user.getUserId(), user.getEmail());
        }
    }

    private void updateUsername(User user, String requestedUsername) throws FatumUserException {
        String username = TextNormalizer.trimOrNull(requestedUsername);
        if (username == null || username.equalsIgnoreCase(user.getUsername())) {
            return;
        }
        validateUniqueUsername(username, user.getAwsId());
        user.setUsername(username);
    }

    private void updatePhoneNumber(User user, String requestedPhoneNumber) throws FatumUserException {
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

    private Address findAddress(User user, String alias) throws FatumUserException {
        requireActive(user);
        Address address = addressRepository.findByUserAwsIdAndAlias(user.getAwsId(), TextNormalizer.lower(alias));
        if (address == null) throw new FatumUserException(FatumUserException.ADDRESS_NOT_FOUND);
        return address;
    }

    private void ensureAliasIsFree(User user, String alias) throws FatumUserException {
        if (addressRepository.existsByUserAwsIdAndAlias(user.getAwsId(), alias)) {
            throw new FatumUserException(FatumUserException.ADDRESS_EXISTS);
        }
    }

    private Address toNewAddress(NewAddressRequest request, User user) throws FatumUserException {
        Address address = AddressMapper.toEntity(request, user);
        if (address == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
        return address;
    }
}
