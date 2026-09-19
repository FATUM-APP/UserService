package fatum.service;

import fatum.dto.UserUpdateRequest;
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

    public boolean validateUser(User user)  {

        return validateUniqueValues(user) && validateNewUser(user);
    }


    public User getUserById(String auth0Id) throws FatumUserException {
        validateText(auth0Id);
        User user = userRepository.findByAuth0Id(auth0Id.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }


    public User getUserByDocument(String document) throws FatumUserException {
        validateText(document);
        User user = userRepository.findByDocument(document.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }


    public User getUserByUsername(String username) throws FatumUserException {
        validateText(username);
        User user = userRepository.findByUsernameIgnoreCase(username.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }


    public User getUserByEmail(String email) throws FatumUserException {
        validateText(email);
        User user = userRepository.findByEmailIgnoreCase(email.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }


    public User getUserByPhoneNumber(String phoneNumber) throws FatumUserException {
        validateText(phoneNumber);
        User user = userRepository.findByPhoneNumber(phoneNumber.trim());
        if (user == null) {
            throw new FatumUserException(FatumUserException.USER_NOT_FOUND);
        }
        return user;
    }


    public List<User> getUsersByName(String name) throws FatumUserException {
        validateText(name);
        return userRepository.findByNamesIgnoreCase(name.trim());
    }

    @Transactional
    public User updateUser(String auth0Id, UserUpdateRequest update) throws FatumUserException {
        if (update == null) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }

        User existingUser = getUserById(auth0Id);
        updateUsername(existingUser, update.username());
        updatePhoneNumber(existingUser, update.phoneNumber());
        updateRoleAndCity(existingUser, update);
        return userRepository.save(existingUser);
    }

    @Transactional
    public void deactivateUser(String auth0Id) throws FatumUserException {
        User user = getUserById(auth0Id);
        user.deactivate();
        userRepository.save(user);
    }

    public boolean userIsAuthenticated(String auth0Id) throws FatumUserException {
        return getUserById(auth0Id).isAuthenticated();
    }

    public boolean isUserActiveByEmail(String email) throws FatumUserException {
        return getUserByEmail(email).isActive();
    }

    private void updateUsername(User user, String requestedUsername) throws FatumUserException {
        String username = normalize(requestedUsername);
        if (username == null || username.equalsIgnoreCase(user.getUsername())) {
            return;
        }
        User conflict = userRepository.findByUsernameIgnoreCase(username);
        if (conflict != null && !conflict.getAuth0Id().equals(user.getAuth0Id())) {
            throw new FatumUserException(FatumUserException.USERNAME_EXISTS);
        }
        user.setUsername(username);
    }

    private void updatePhoneNumber(User user, String requestedPhoneNumber)
            throws FatumUserException {
        String phoneNumber = normalize(requestedPhoneNumber);
        if (phoneNumber == null || phoneNumber.equals(user.getPhoneNumber())) {
            return;
        }
        User conflict = userRepository.findByPhoneNumber(phoneNumber);
        if (conflict != null && !conflict.getAuth0Id().equals(user.getAuth0Id())) {
            throw new FatumUserException(FatumUserException.PHONE_EXISTS);
        }
        user.setPhoneNumber(phoneNumber);
    }

    private void updateRoleAndCity(User user, UserUpdateRequest update)
            throws FatumUserException {
        String city = normalize(update.city());
        UserRole targetRole = update.role() == null ? user.getRole() : update.role();
        String effectiveCity = city == null ? user.getCity() : city;

        if (targetRole == UserRole.PROFESSIONAL
                && (effectiveCity == null || effectiveCity.isBlank())) {
            throw new FatumUserException(FatumUserException.PROFESSIONAL_CITY);
        }
        if (city != null) {
            user.setCity(city);
        }
        if (update.role() != null) {
            user.setRole(update.role());
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
        if (userRepository.findByAuth0Id(user.getAuth0Id()) != null) {
            throw new FatumUserException(FatumUserException.USER_ALREADY_EXISTS);
        }
        if (userRepository.findByEmailIgnoreCase(user.getEmail()) != null) {
            throw new FatumUserException(FatumUserException.EMAIL_EXISTS);
        }
        if (userRepository.findByPhoneNumber(user.getPhoneNumber()) != null) {
            throw new FatumUserException(FatumUserException.PHONE_EXISTS);
        }
        if (userRepository.findByUsernameIgnoreCase(user.getUsername()) != null) {
            throw new FatumUserException(FatumUserException.USERNAME_EXISTS);
        }
        if (userRepository.findByDocument(user.getDocument()) != null) {
            throw new FatumUserException(FatumUserException.DOCUMENT_EXISTS);
        }
    }

    private void validateText(String value) throws FatumUserException {
        if (value == null || value.isBlank()) {
            throw new FatumUserException(FatumUserException.NULL_VALUE);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
