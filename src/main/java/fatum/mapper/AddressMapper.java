package fatum.mapper;

import fatum.dto.NewAddressRequest;
import fatum.model.Address;
import fatum.model.User;

public class AddressMapper {
    private AddressMapper() {}

    public static Address toEntity(NewAddressRequest request, User user) {
        return new Address(
                request.residence().trim().toLowerCase(),
                request.alias().toLowerCase().trim(),
                request.city().trim().toLowerCase(),
                request.country().trim().toLowerCase(),
                user,
                request.state().trim().toLowerCase()
        );
    }
}
