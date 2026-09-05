package fatum.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Payload used to create the user associated with the authenticated JWT subject.
 */
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

        @NotNull(message = "Birth date is required")
        @Past(message = "Birth date must be in the past")
        LocalDate birthDate
) {
}
