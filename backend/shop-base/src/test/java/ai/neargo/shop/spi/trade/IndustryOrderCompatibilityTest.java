package ai.neargo.shop.spi.trade;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

class IndustryOrderCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void oldRegisteredBuyerCommandIdentityRetainsItsCanonicalFingerprint() {
        var old =
                Map.of(
                        "tenantNo",
                        "MAIN",
                        "entityNo",
                        "E",
                        "storeNo",
                        "S",
                        "buyerNo",
                        "U",
                        "actorNo",
                        "U");
        assertThat(PricingSnapshotHash.of(new Identity("MAIN", "E", "S", "U", "U")))
                .isEqualTo(PricingSnapshotHash.of(old));
    }

    @Test
    void originalPersistedAllocationWithoutExactFieldsRemainsReadable() {
        var allocation =
                json.readValue(
                        """
                        {"sourceLineNo":"L","componentRef":"C","orderItemId":4,"qty":2,
                         "amountMinor":100,"currency":"CNY","hash":"H"}
                        """,
                        Allocation.class);
        assertThat(allocation.saleQuantity()).isEqualByComparingTo("2");
        assertThat(allocation.quantityUnit()).isEqualTo("EA");
    }

    @Test
    void anonymousPartyIsSeparateFromPlatformBuyerIdentity() {
        var party = new Identity("MAIN", "E", "S", null, "STAFF", "GUEST");
        assertThat(party.buyerNo()).isEmpty();
        assertThat(PricingSnapshotHash.of(party))
                .isNotEqualTo(
                        PricingSnapshotHash.of(
                                new Identity("MAIN", "E", "S", null, "STAFF", "OTHER")));
    }
}
