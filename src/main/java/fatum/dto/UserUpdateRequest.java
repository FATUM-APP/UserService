package fatum.dto;

import fatum.model.constant.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(
        @Pattern(regexp = ".*\\S.*", message = "Username must not be blank")
        @Size(max = 15, message = "Username must not exceed 15 characters")
        String username,

        @Pattern(regexp = ".*\\S.*", message = "Phone number must not be blank")
        @Size(max = 20, message = "Phone number must not exceed 20 characters")
        String phoneNumber,

        UserRole role,

        @Pattern(regexp = ".*\\S.*", message = "City must not be blank")
        @Size(max = 50, message = "City must not exceed 50 characters")
        String city,

        @Valid
        NewAddressRequest newAddress
) {

    /**
     * Cleans the text of the fields that were sent.
     *
     * <p>An omitted field stays {@code null}, which is what the service reads as "do not touch it".
     * A field made of spaces becomes empty, so the pattern that forbids blank values rejects it.</p>
     */
    public UserUpdateRequest {
        username = TextNormalizer.lower(username);
        phoneNumber = TextNormalizer.trim(phoneNumber);
        city = TextNormalizer.lower(city);
    }
}