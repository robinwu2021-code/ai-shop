package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Typed cart boundary keeps draft identities separate from authoritative quotes and orders. */
public record CartSelectionInput(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String groupNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceKind,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceNo,
        @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal qty,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long configVersion) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown cart field"); }
}
