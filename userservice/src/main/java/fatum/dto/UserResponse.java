package fatum.dto;

import fatum.model.constant.DocumentType;

import java.time.LocalDate;

public record UserResponse(
        String userId,
        String email,
        String name,
        LocalDate birthDate,
        String username,
        String phoneNumber,
        UserRole role,
        VerificationStatus verificationStatus,
        boolean isActive,
        String document,
        Gender gender,
        DocumentType documentType,
        AddressDTO[] addresses
) {
}