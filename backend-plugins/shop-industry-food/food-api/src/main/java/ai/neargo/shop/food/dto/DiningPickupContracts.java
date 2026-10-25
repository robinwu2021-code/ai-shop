package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Receipt;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Original customer pickup code is verified by trade; local handover remains a separate service
 * fact.
 */
public final class DiningPickupContracts {
    private DiningPickupContracts() {}

    public record VerifyPickupReq(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) @NotNull
                    Long expectedVersion,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    @NotBlank
                    @Pattern(regexp = "[0-9]{6}")
                    String verifyCode,
            String custodyOperationNo) {
        public VerifyPickupReq(Long expectedVersion, String verifyCode) {
            this(expectedVersion, verifyCode, null);
        }

        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException(name);
        }

        @Override
        public String toString() {
            return "DiningPickupVerification[REDACTED]";
        }
    }

    public record PickupVerificationVO(
            String batchNo, boolean localHandoverRecorded, Receipt originalFulfillment) {}
}
