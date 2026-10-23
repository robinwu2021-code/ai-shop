package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Restriction facts omit physical row ids; EXPIRED is evaluated against the server clock. */
public record SalesRestrictionVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String restrictionNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String mode,
        LocalDate businessDate,
        @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quota,
        @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal reservedQty,
        @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal consumedQty,
        @JsonFormat(shape=JsonFormat.Shape.STRING)
        @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant startsAt,
        @JsonFormat(shape=JsonFormat.Shape.STRING)
        @JsonDeserialize(using=SalesRestrictionPolicy.InstantDeserializer.class) Instant expiresAt,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version) { }
