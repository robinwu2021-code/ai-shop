package ai.neargo.shop.trade.order.service.query;

import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestRef;

import java.util.List;

/**
 * Constructed from the actual consumer and complete current member proof, never public query
 * selectors.
 */
public record CustomerReadScope(String userNo, List<GuestRef> claimedGuests) {
    public CustomerReadScope {
        if (userNo == null
                || userNo.isBlank()
                || userNo.length() > 64
                || !userNo.equals(userNo.strip())) {
            throw new IllegalArgumentException("current customer required");
        }
        claimedGuests = List.copyOf(claimedGuests);
    }
}
