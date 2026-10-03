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

        @NotBlank(message = "State must not be blank")
        @Size(max = 50, message = "State must not exceed 50 characters")
        String state,

        @NotBlank(message = "Country must not be blank")
        @Size(max = 50, message = "Country must not exceed 50 characters")
        String country
) {

    /**
     * Cleans every text field of the address.
     *
     * <p>Addresses are stored folded to lower case, because that is the form the lookup by residence
     * and the allowed-location list use. The rule lives here so the mapper and the entity do not
     * repeat it.</p>
     */
    public NewAddressRequest {
        residence = TextNormalizer.lower(residence);
        alias = TextNormalizer.lower(alias);
        city = TextNormalizer.lower(city);
        state = TextNormalizer.lower(state);
        country = TextNormalizer.lower(country);
    }
}