package fatum.dto;

import fatum.model.DocumentType;
import fatum.model.UserRole;

import java.time.LocalDate;

public record UserResponse(
        String auth0Id,
        String email,
        String names,
        String surnames,
        LocalDate birthDate,
        String username,
        String phoneNumber,
        UserRole role,
        boolean isAuthenticated,
        boolean isActive,
        String document,
        DocumentType documentType,
        String city,
        ProfileImageResponse profileImage
) {
}
