package fatum.dto;

import fatum.model.User;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class UserMapper {

    public User toEntity(String auth0Id, CreateUserRequest request) {
        Objects.requireNonNull(request, "request is required");
        return new User(
                auth0Id,
                request.email(),
                request.name(),
                request.phoneNumber(),
                request.birthDate(),
                request.username(),
                request.document(),
                request.documentType());
    }

    public UserResponse toResponse(
            User user,
            StoredFileResponse profileImage,
            StoredFileResponse documentFile) {
        Objects.requireNonNull(user, "user is required");
        return new UserResponse(
                user.getAuth0Id(),
                user.getEmail(),
                user.getName(),
                user.getBirthDate(),
                user.getUsername(),
                user.getPhoneNumber(),
                user.getRole(),
                user.isAuthenticated(),
                user.isActive(),
                user.getDocument(),
                user.getDocumentType(),
                user.getCity(),
                user.getCountry(),
                profileImage,
                documentFile);
    }
}
