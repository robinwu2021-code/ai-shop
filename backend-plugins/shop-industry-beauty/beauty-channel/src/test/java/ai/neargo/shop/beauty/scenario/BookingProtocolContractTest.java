package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.Test;

/** Neutral owner and reverse source protocols must exist independently of the beauty core. */
class BookingProtocolContractTest {
    @Test void typedOwnerCustomerAndReverseSourceContractsExist() {
        for (String type : new String[] {"ai.neargo.shop.spi.resource.ResourceBookingInternalApi",
                "ai.neargo.shop.spi.member.CustomerIdentityInternalApi",
                "ai.neargo.shop.beauty.spi.BeautySourceInternalApi"}) {
            assertDoesNotThrow(() -> Class.forName(type));
        }
    }
}
