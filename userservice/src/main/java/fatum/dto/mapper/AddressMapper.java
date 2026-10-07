package fatum.dto.mapper;

import fatum.dto.AddressDTO;
import fatum.dto.NewAddressRequest;
import fatum.model.Address;
import fatum.model.User;

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
     * @param address the stored address
     * @param user    name of the account the address belongs to
     * @return the address as the client sees it, or {@code null} when there is no address
     */
    public static AddressDTO toDTO(Address address, String user) {

        return address != null ?new AddressDTO(
                address.getResidence(),
                address.getAlias(),
                address.getCity(),
                address.getState(),
                address.getCountry(),
                user) : null;
    }

    /**
     * Maps a whole list without touching the order.
     *
     * <p>The order is the contract: the collection is stored ordered and the first element is the
     * principal address, so this method never sorts nor filters. An address that cannot be mapped
     * keeps its place as {@code null} for the same reason.</p>
     */
    public static List<AddressDTO> toDTOList(List<Address> addresses, String user) {
        return addresses.stream()
                .map(address -> toDTO(address, user))
                .toList();
    }
}