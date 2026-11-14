package ai.neargo.shop.food.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonInclude;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;
import java.util.List;

/** Original schema one and market-context schema six share one closed definition with original dependency facts. */
public record PublicationSnapshot(int contentSchema, String configKind, String configNo, @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion,
        ModifierConfigVO modifier, OptionGroupConfigVO optionGroup, List<Dependency> dependencies,
        List<SkuIdentityFact> skuFacts, List<SpecValueFact> specFacts, String approvalRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) PublicationMarketContext marketContext) {
    public PublicationSnapshot {
        dependencies = List.copyOf(dependencies);
        skuFacts = List.copyOf(skuFacts);
        specFacts = List.copyOf(specFacts);
    }
    /** Old constructors intentionally omit market context so frozen legacy bytes and hashes remain stable. */
    public PublicationSnapshot(int contentSchema, String configKind, String configNo, long sourceVersion,
            ModifierConfigVO modifier, OptionGroupConfigVO optionGroup, List<Dependency> dependencies,
            List<SkuIdentityFact> skuFacts, List<SpecValueFact> specFacts, String approvalRef) {
        this(contentSchema, configKind, configNo, sourceVersion, modifier, optionGroup, dependencies,
                skuFacts, specFacts, approvalRef, null);
    }

    public record Dependency(String revisionNo, String configKind, String configNo, @JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision,
            String contentHash) { }
}
