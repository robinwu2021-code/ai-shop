package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Bundle schemas two and six freeze explicit composition; market context never grants sale or stock authority. */
public record BundlePublicationSnapshot(int contentSchema, String configKind, String configNo,
        @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion,
        BundleConfigVO bundle, List<SkuIdentityFact> skuFacts, String approvalRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) PublicationMarketContext marketContext) {
    public BundlePublicationSnapshot { skuFacts = List.copyOf(skuFacts); }

    /** Old constructors intentionally omit market context so frozen legacy bytes and hashes remain stable. */
    public BundlePublicationSnapshot(int contentSchema, String configKind, String configNo, long sourceVersion,
            BundleConfigVO bundle, List<SkuIdentityFact> skuFacts, String approvalRef) {
        this(contentSchema, configKind, configNo, sourceVersion, bundle, skuFacts, approvalRef, null);
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown bundle snapshot field");
    }
}
