package fatum.dto;

import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateUserRequest(
        @NotBlank(message = "AWS id is required")
        @Size(max = 255)
        String awsId,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 100, message = "Email must not exceed 100 characters")
        String email,

        @NotBlank(message = "Name is required")
        @Size(max = 142, message = "Name must not exceed 142 characters")
        String name,

        @NotBlank(message = "Phone number is required")
        @Size(max = 20, message = "Phone number must not exceed 20 characters")
        String phoneNumber,

        @NotNull(message = "Birth date is required")
        @Past(message = "Birth date must be in the past")
        LocalDate birthDate,

        @NotBlank(message = "Username is required")
        @Size(max = 15, message = "Username must not exceed 15 characters")
        String username,

        @NotBlank(message = "Document is required")
        @Size(max = 30, message = "Document must not exceed 30 characters")
        String document,

        @NotNull(message = "Document type is required")
        DocumentType documentType,

        @NotNull(message = "Gender is required")
        Gender gender
) {

    /**
     * Cleans the text before anything else sees it.
     *
     * <p>Jackson builds the record and this constructor runs before Bean Validation, so the
     * annotations above judge the value the application will actually store: a field made of spaces
     * becomes empty and {@code @NotBlank} rejects it.</p>
     */
    public CreateUserRequest {
        awsId = TextNormalizer.trim(awsId);
        email = TextNormalizer.lower(email);
        name = TextNormalizer.upper(name);
        phoneNumber = TextNormalizer.trim(phoneNumber);
        username = TextNormalizer.lower(username);
        document = TextNormalizer.upper(document);
    }
}