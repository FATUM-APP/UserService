package fatum.mapper;

import fatum.dto.CreateUserRequest;
import fatum.dto.UserResponse;
import fatum.exception.FatumUserException;
import fatum.model.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    private UserMapper() {}

    public static User toEntity(CreateUserRequest request) throws FatumUserException {
        if (request == null) throw new FatumUserException(FatumUserException.NULL_VALUE);
        return new User.Builder()
                .awsId(request.awsId())
                .email(request.email())
                .name(request.name())
                .phoneNumber(request.phoneNumber())
                .birthDate(request.birthDate())
                .username(request.username())
                .document(request.document())
                .documentType(request.documentType())
                .gender(request.gender())
                .build();
    }


        public static UserResponse toResponse (User user) throws FatumUserException{
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
                    user.getDocumentType());
        }


    }
