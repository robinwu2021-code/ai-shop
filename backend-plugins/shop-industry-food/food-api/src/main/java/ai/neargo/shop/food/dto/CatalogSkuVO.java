package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import tools.jackson.databind.annotation.JsonDeserialize;
import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.spi.product.DishOfferFacts;
import java.math.BigDecimal;

/** Unknown price and physical stock stay null; transactable is only a current display hint. */
public record CatalogSkuVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String spec,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo,
        @JsonDeserialize(using=DishOfferFacts.MinorDeserializer.class) Long priceMinor,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String currency,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String pricingStatus,
        @JsonDeserialize(using=DishOfferFacts.AvailableDeserializer.class) Integer available,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stockStatus,
        @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean onSale,
        @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean restricted,
        @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal remainingQuota,
        @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean transactable,
        @JsonDeserialize(using=MenuDefinitionPolicy.BooleanDeserializer.class) Boolean requiresSelectionQuote,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason) { }
