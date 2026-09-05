package fatum.dto;

import fatum.model.ProfileImage;
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
                request.names(),
                request.surnames(),
                request.phoneNumber(),
                request.birthDate(),
                request.username(),
                request.document(),
                request.documentType());
    }

    public UserResponse toResponse(User user, String profileImageUrl) {
        Objects.requireNonNull(user, "user is required");
        ProfileImageResponse profileImage = toProfileImageResponse(user.getProfileImage(), profileImageUrl);
        return new UserResponse(
                user.getAuth0Id(),
                user.getEmail(),
                user.getNames(),
                user.getSurnames(),
                user.getBirthDate(),
                user.getUsername(),
                user.getPhoneNumber(),
                user.getRole(),
                user.isAuthenticated(),
                user.isActive(),
                user.getDocument(),
                user.getDocumentType(),
                user.getCity(),
                profileImage);
    }

    private ProfileImageResponse toProfileImageResponse(ProfileImage image, String profileImageUrl) {
        if (image == null) {
            return null;
        }
        return new ProfileImageResponse(image.getId(), profileImageUrl);
    }
}
