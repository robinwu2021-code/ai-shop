package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Custody records physical customer property; no amount here grants a sales or inventory posting.
 */
public final class BottleCustodyContracts {
    private BottleCustodyContracts() {}

    public record Deposit(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String customerNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo,
            @JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class)
                    BigDecimal quantity,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String unit,
            Instant expiresAt,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reason,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String originalReceiptRef) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException(key);
        }
    }

    public record Withdraw(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String action,
            @JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class)
                    BigDecimal quantity,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reason,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String recipientNo) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException(key);
        }
    }

    public record Custody(
            String custodyNo,
            String entityNo,
            String storeNo,
            String customerNo,
            String skuNo,
            BigDecimal deposited,
            BigDecimal remaining,
            String unit,
            Instant expiresAt,
            String status,
            long version) {}

    public record Movement(
            String movementNo,
            String custodyNo,
            String action,
            BigDecimal quantity,
            BigDecimal remaining,
            String unit,
            String actorNo,
            String recipientNo,
            String reason,
            Instant occurredAt) {}

    public record Result(Custody custody, Movement movement) {}
}
