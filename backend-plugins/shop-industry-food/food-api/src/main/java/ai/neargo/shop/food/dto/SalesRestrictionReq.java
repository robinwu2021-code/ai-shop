package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Commands change restaurant restrictions; physical inventory and quota counters are never caller controlled. */
public record SalesRestrictionReq(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String mode,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String restrictionNo,
        @JsonDeserialize(using=SalesRestrictionPolicy.OptionalVersionDeserializer.class) Long version,
        @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quota,
        LocalDate businessDate,
        @JsonFormat(shape=JsonFormat.Shape.STRING)
        @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant startsAt,
        @JsonFormat(shape=JsonFormat.Shape.STRING)
        @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant expiresAt,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment) {
    @JsonAnySetter public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown sales restriction command field");
    }
}
