package fatum.dto;

/**
 * An address as the client sees it.
 *
 * <p>{@code principal} is derived, not stored: the collection is kept ordered and the first element
 * is the principal address, so the flag is computed while mapping. It travels explicitly because
 * "the first one of the array" is a contract nobody documents.</p>
 */
public record AddressDTO(
        String residence,
        String alias,
        String city,
        String state,
        String country,
        String userName
) {
}
