package fatum.dto;

import fatum.model.constant.Country;
import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import fatum.model.constant.UserRole;

import java.time.LocalDate;

public record UserResponse(
        String email,
        String name,
        LocalDate birthDate,
        String username,
        String phoneNumber,
        UserRole role,
        boolean isAuthenticated,
        boolean isActive,
        String document,
        Gender gender,
        DocumentType documentType,
        String city,
        Country country
) {
}
