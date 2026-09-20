package fatum.service;

import fatum.exception.FatumUserException;
import fatum.model.User;
import fatum.model.constant.UserRole;
import fatum.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User createUser(User newUser)  {
        return userRepository.save(newUser);
    }

    public void validateUser(User user) throws FatumUserException {
        validateNewUser(user);
        validateUniqueValues(user);
    }


    public User getUserById(String awsId) throws FatumUserException {
        User user = userRepository.findByAwsId(normalize(awsId));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User userExistsById(String awsId) {
        return userRepository.findByAwsId(normalize(awsId));
    }


    public User getUserByDocument(String document) throws FatumUserException {
        User user = userRepository.findByDocumentIgnoreCase(normalize(document));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User userExistsByDocument(String document) {
        return userRepository.findByDocumentIgnoreCase(normalize(document));
    }


    public User getUserByUsername(String username) throws FatumUserException {
             User user = userRepository.findByUsernameIgnoreCase(normalize( username));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User userExistsByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(normalize( username));
    }

    public User getUserByEmail(String email) throws FatumUserException {
        User user = userRepository.findByEmailIgnoreCase(normalize(email));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User userExistsByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(normalize(email));
    }


    public User getUserByPhoneNumber(String phoneNumber) throws FatumUserException {
        User user = userRepository.findByPhoneNumber(normalize(phoneNumber));
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }

    public User userExistsByPhoneNumber(String phoneNumber) {
        return userRepository.findByPhoneNumber(normalize(phoneNumber));
    }


    public List<User> getUsersByName(String name) {
        return userRepository.findByNameIgnoreCase(normalize(name));
    }

    @Transactional
    public User updateUser(
            String awsId,
            String username,
            String phoneNumber,
            UserRole role,
            String city) throws FatumUserException {

        User existingUser = getUserById(awsId);
        updateUsername(existingUser, username);
        updatePhoneNumber(existingUser, phoneNumber);
        updateRoleAndCity(existingUser, role, city);
        return userRepository.save(existingUser);
    }

    @Transactional
    public void deactivateUser(String email) throws FatumUserException {
        User user = getUserByEmail(email);
        user.deactivate();
        userRepository.save(user);
    }

    public boolean userIsAuthenticated(String awsId) throws FatumUserException {
        return getUserById(awsId).isAuthenticated();
    }

    public boolean isUserActiveByEmail(String email) throws FatumUserException {
        return getUserByEmail(email).isActive();
    }

    private void updateUsername(User user, String requestedUsername) throws FatumUserException {
        String username = normalize(requestedUsername);
        if (username == null || username.equalsIgnoreCase(user.getUsername())) {
            return;
        }
        validateUniqueUsername(username, user.getAwsId());
        user.setUsername(username);
    }

    private void updatePhoneNumber(User user, String requestedPhoneNumber)
            throws FatumUserException {
        String phoneNumber = normalize(requestedPhoneNumber);
        if (phoneNumber == null || phoneNumber.equals(user.getPhoneNumber())) {
            return;
        }
        validateUniquePhoneNumber(phoneNumber, user.getAwsId());
        user.setPhoneNumber(phoneNumber);
    }

    private void updateRoleAndCity(User user, UserRole role, String newCity)
            throws FatumUserException {
        String city = normalize(newCity);
        UserRole targetRole = role== null ? user.getRole() : role;
        String effectiveCity = city == null ? user.getCity() : city;

        if (targetRole == UserRole.PROFESSIONAL
                && (effectiveCity == null || effectiveCity.isBlank())) {
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        }
        if (city != null) {
            user.setCity(city);
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

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
