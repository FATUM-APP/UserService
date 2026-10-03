package fatum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NewAddressRequest(
        @NotBlank(message = "Residence must not be blank")
        @Size(max = 255, message = "Residence must not exceed 255 characters")
        String residence,

        @Size(max = 255, message = "Alias must not exceed 255 characters")
        String alias,

        @NotBlank(message = "City must not be blank")
        @Size(max = 50, message = "City must not exceed 50 characters")
        String city,

        @NotBlank(message = "City must not be blank")
        @Size(max = 50, message = "State must not exceed 50 characters")
        String state,

        @NotBlank(message = "Country must not be blank")
        @Size(max = 50, message = "Country must not exceed 50 characters")
        String country
) {
}