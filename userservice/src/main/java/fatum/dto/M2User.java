package fatum.dto;

import fatum.model.constant.DocumentType;
import fatum.model.constant.Gender;
import fatum.model.constant.UserRole;
import fatum.model.constant.VerificationStatus;

import java.time.LocalDate;

public record M2User(
        String userId,
        LocalDate birthDate,
        boolean isProfessional,
        boolean isVerified,
        boolean isActive,
        Gender gender,
        AddressDTO principalAddress
) {
}