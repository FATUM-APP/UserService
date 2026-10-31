package fatum.dto;

import fatum.common.dto.user.AddressDTO;
import fatum.common.enums.user.Gender;
import fatum.common.enums.user.UserRole;
import fatum.common.enums.user.VerificationStatus;
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