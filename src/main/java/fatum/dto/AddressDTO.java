package fatum.dto;

/**
 * An address as the client sees it.
 *
 * <p>There is no flag for the principal address on purpose: the addresses are stored ordered and the
 * first one is the principal one, so the position already says it and a field would only be a copy
 * that can disagree with the order.</p>
 *
 * <p>{@code userName} names the account the address belongs to, so a component that renders a list
 * can show whose address it is.</p>
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