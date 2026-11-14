package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Recipe formats three and six freeze base-only facts; market context never grants inventory posting authority. */
public record RecipePublicationSnapshot(int contentSchema, String configKind, String configNo,
        @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion,
        RecipeVO recipe, List<SkuIdentityFact> skuFacts, List<MaterialFact> materialFacts,
        List<RecipeOptionGroupSnapshot> optionGroups, String approvalRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) PublicationMarketContext marketContext) {
    public RecipePublicationSnapshot {
        skuFacts = List.copyOf(skuFacts);
        materialFacts = List.copyOf(materialFacts);
        optionGroups = List.copyOf(optionGroups);
    }
    /** Old constructors intentionally omit market context so frozen legacy bytes and hashes remain stable. */
    public RecipePublicationSnapshot(int contentSchema, String configKind, String configNo, long sourceVersion,
            RecipeVO recipe, List<SkuIdentityFact> skuFacts, List<MaterialFact> materialFacts,
            List<RecipeOptionGroupSnapshot> optionGroups, String approvalRef) {
        this(contentSchema, configKind, configNo, sourceVersion, recipe, skuFacts, materialFacts,
                optionGroups, approvalRef, null);
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown recipe snapshot field");
    }
}
