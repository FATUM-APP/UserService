package fatum.dto;

import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;

import java.time.LocalDate;

public record UserResponse(
        String id,
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