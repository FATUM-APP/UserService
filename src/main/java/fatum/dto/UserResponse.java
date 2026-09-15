package fatum.dto;

import fatum.model.constant.Country;
import fatum.model.constant.DocumentType;
import fatum.model.constant.UserRole;

import java.time.LocalDate;

public record UserResponse(
        String auth0Id,
        String email,
        String name,
        LocalDate birthDate,
        String username,
        String phoneNumber,
        UserRole role,
        boolean isAuthenticated,
        boolean isActive,
        String document,
        DocumentType documentType,
        String city,
        Country country,
        StoredFileResponse profileImage,
        StoredFileResponse documentFile
) {
}
