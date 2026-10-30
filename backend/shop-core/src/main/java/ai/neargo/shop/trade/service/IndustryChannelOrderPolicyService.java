package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Merchant-controlled receivable readiness; platform collection is not a local payment
 * acknowledgement.
 */
public interface IndustryChannelOrderPolicyService {
    record SaveReq(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String currency,
            @JsonDeserialize(using = IndustryChannelOrderFacts.BooleanValue.class) Boolean enabled,
            @JsonDeserialize(using = IndustryChannelOrderFacts.BooleanValue.class)
                    Boolean allowPlatformCollected,
            @JsonDeserialize(using = IndustryChannelOrderFacts.BooleanValue.class)
                    Boolean allowCashOnDelivery,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long maxSingleMinor,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long maxOutstandingMinor,
            @JsonDeserialize(using = InventoryReservationPort.IntegerValue.class)
                    Integer holdSeconds) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown channel admission policy field");
        }
    }

    record Policy(
            String policyNo,
            String channelStoreKey,
            String currency,
            boolean enabled,
            boolean allowPlatformCollected,
            boolean allowCashOnDelivery,
            long maxSingleMinor,
            long maxOutstandingMinor,
            long reservedMinor,
            int holdSeconds,
            long version) {}

    PageData<Policy> list(Scope scope, int page, int size);

    Policy get(Scope scope, String channelStoreKey);

    Policy save(Scope scope, String actorNo, String channelStoreKey, SaveReq request, String key);
}
