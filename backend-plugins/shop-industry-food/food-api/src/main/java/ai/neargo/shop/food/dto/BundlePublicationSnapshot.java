package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Schema two freezes explicit bundle composition without guessing mutable child option or recipe bindings. */
public record BundlePublicationSnapshot(int contentSchema, String configKind, String configNo,
        @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion,
        BundleConfigVO bundle, List<SkuIdentityFact> skuFacts, String approvalRef) {
    public BundlePublicationSnapshot { skuFacts = List.copyOf(skuFacts); }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown bundle snapshot field");
    }
}
