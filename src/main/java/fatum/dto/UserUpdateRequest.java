package fatum.dto;

import fatum.model.DocumentType;
import fatum.model.UserRole;
import jakarta.validation.constraints.Size;

/**
 * Partial user update. Null fields are ignored.
 */
public record UserUpdateRequest(
        @Size(max = 15, message = "Username must not exceed 15 characters")
        String username,

        @Size(max = 20, message = "Phone number must not exceed 20 characters")
        String phoneNumber,

        UserRole role,

        @Size(max = 30, message = "Document must not exceed 30 characters")
        String document,

        DocumentType documentType,

        @Size(max = 50, message = "City must not exceed 50 characters")
        String city
) {
}
