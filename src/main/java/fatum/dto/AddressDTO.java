package fatum.dto;

public record AddressDTO(
        String residence,
        String alias,
        String city,
        String state,
        String country,
        String userName
) {
}
