package ai.neargo.shop.food.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;
import java.util.List;

/** Schema-one closed document: exactly one definition plus append-only dependency identities and source facts. */
public record PublicationSnapshot(int contentSchema, String configKind, String configNo, @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion,
        ModifierConfigVO modifier, OptionGroupConfigVO optionGroup, List<Dependency> dependencies,
        List<SkuIdentityFact> skuFacts, List<SpecValueFact> specFacts, String approvalRef) {
    public PublicationSnapshot {
        dependencies = List.copyOf(dependencies);
        skuFacts = List.copyOf(skuFacts);
        specFacts = List.copyOf(specFacts);
    }
    public record Dependency(String revisionNo, String configKind, String configNo, @JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision,
            String contentHash) { }
}
