package fatum.mapper;

import fatum.dto.NewAddressRequest;
import fatum.model.Address;
import fatum.model.User;

/**
 * Turns an address request into the entity.
 *
 * <p>The text arrives already normalised by {@link NewAddressRequest}, so this mapper only moves
 * values around.</p>
 */
public class AddressMapper {

    private AddressMapper() {
    }

    public static Address toEntity(NewAddressRequest request, User user) {
        return new Address(
                request.residence(),
                request.alias(),
                request.city(),
                request.country(),
                user,
                request.state());
    }
}