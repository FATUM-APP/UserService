package fatum.dto;

import fatum.model.DocumentType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateUserRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 100, message = "Email must not exceed 100 characters")
        String email,

        @NotBlank(message = "Names are required")
        @Size(max = 70, message = "Names must not exceed 70 characters")
        String names,

        @NotBlank(message = "Surnames are required")
        @Size(max = 70, message = "Surnames must not exceed 70 characters")
        String surnames,

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
        DocumentType documentType
) {
}
