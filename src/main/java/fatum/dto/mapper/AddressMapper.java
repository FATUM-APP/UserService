package fatum.dto.mapper;

import fatum.dto.AddressDTO;
import fatum.dto.NewAddressRequest;
import fatum.model.Address;
import fatum.model.User;

import java.util.ArrayList;
import java.util.List;

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

        return request != null ?new Address(
                request.residence(),
                request.alias(),
                request.city(),
                request.country(),
                user,
                request.state()) : null;
    }

    /**
     * Maps one address.
     *
     * <p>The principal flag is a parameter because only the caller knows where the address sits
     * inside the account: the entity stores the order, not the flag.</p>
     */
    public static AddressDTO toDTO(Address address, String user, boolean principal) {

        return address != null ?new AddressDTO(
                address.getResidence(),
                address.getAlias(),
                address.getCity(),
                address.getState(),
                address.getCountry(),
                user,
                principal) : null;
    }

    /**
     * Maps a whole list, marking the first one as the principal.
     *
     * <p>The order is the contract here: the collection is stored ordered, so the first element is
     * the principal address and there is no need to look it up again.</p>
     */
    public static List<AddressDTO> toDTOList(List<Address> addresses, String user) {
        List<AddressDTO> result = new ArrayList<>(addresses.size());
        for (int index = 0; index < addresses.size(); index++) {
            result.add(toDTO(addresses.get(index), user, index == 0));
        }
        return result;
    }
}