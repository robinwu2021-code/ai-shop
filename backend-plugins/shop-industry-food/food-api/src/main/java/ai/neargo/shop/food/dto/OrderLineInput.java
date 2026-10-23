package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Typed cart boundary keeps draft identities separate from authoritative quotes and orders. */
public record OrderLineInput(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceLineRef,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuItemNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuRevisionNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String goodsNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
        @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal qty,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String seatNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String courseNo,
        List<CartSelectionInput> selections,
        List<CartBundleChoiceInput> bundleChoices) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown cart field"); }
    public OrderLineInput {
        if (selections != null) selections = List.copyOf(selections);
        if (bundleChoices != null) bundleChoices = List.copyOf(bundleChoices);
    }
}
