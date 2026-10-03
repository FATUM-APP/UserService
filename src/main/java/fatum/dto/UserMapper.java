package fatum.dto;

import fatum.exception.FatumUserException;
import fatum.model.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    private UserMapper() {}

    public static User toEntity(CreateUserRequest request) throws FatumUserException{
        if (request == null) throw new FatumUserException(FatumUserException.NULL_VALUE);
        return new User(
                request.awsId(),
                request.email(),
                request.name(),
                request.phoneNumber(),
                request.birthDate(),
                request.username(),
                request.document(),
                request.documentType(),
                request.gender()
        );
    }

    public static UserResponse toResponse(User user) throws FatumUserException {
        if (user == null) throw new FatumUserException(FatumUserException.NULL_VALUE);
        return new UserResponse(
                user.getEmail(),
                user.getName(),
                user.getBirthDate(),
                user.getUsername(),
                user.getPhoneNumber(),
                user.getRole(),
                user.getVerificationStatus(),
                user.isActive(),
                user.getDocument(),
                user.getGender(),
                user.getDocumentType(),
                user.getCity(),
                user.getCountry());
    }
}