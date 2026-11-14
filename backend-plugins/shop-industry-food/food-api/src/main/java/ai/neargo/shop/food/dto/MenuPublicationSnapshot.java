package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Menu formats four and six preserve original display and dependencies without granting price or stock authority. */
public record MenuPublicationSnapshot(int contentSchema,String configKind,String configNo,
        @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long sourceVersion,
        Captured menu,List<SkuIdentityFact> skuFacts,List<GoodsFact> goodsFacts,String approvalRef,
        @JsonInclude(JsonInclude.Include.NON_NULL) PublicationMarketContext marketContext) {
    public MenuPublicationSnapshot { skuFacts=List.copyOf(skuFacts); goodsFacts=List.copyOf(goodsFacts); }
    /** Old constructors intentionally omit market context so frozen legacy bytes and hashes remain stable. */
    public MenuPublicationSnapshot(int contentSchema, String configKind, String configNo, long sourceVersion,
            Captured menu, List<SkuIdentityFact> skuFacts, List<GoodsFact> goodsFacts, String approvalRef) {
        this(contentSchema, configKind, configNo, sourceVersion, menu, skuFacts, goodsFacts, approvalRef, null);
    }

    @JsonAnySetter public void rejectUnknown(String key,Object value) {
        throw new IllegalArgumentException("Unknown menu snapshot field");
    }
    public record Captured(String entityNo,String storeNo,MenuConfigResultVO definition,List<DishVO> dishes,
            List<ItemOptions> itemOptions,List<Dependency> dependencies,List<MenuChannelBindingVO> channelBindings) {
        public Captured {
            dishes=List.copyOf(dishes); itemOptions=List.copyOf(itemOptions);
            dependencies=List.copyOf(dependencies); channelBindings=List.copyOf(channelBindings);
        }
        @JsonAnySetter public void rejectUnknown(String key,Object value) {
            throw new IllegalArgumentException("Unknown captured menu field");
        }
    }
    public record ItemOptions(String menuItemNo,List<String> optionGroupNos) {
        public ItemOptions { optionGroupNos=List.copyOf(optionGroupNos); }
        @JsonAnySetter public void rejectUnknown(String key,Object value) {
            throw new IllegalArgumentException("Unknown frozen item options field");
        }
    }
    public record Dependency(String revisionNo,String configKind,String configNo,
            @JsonProperty(required=true) @JsonDeserialize(using=CatalogRevisionDeserializer.class) long revision,
            int contentSchema,String contentHash,String definitionSnapshot) {
        @JsonAnySetter public void rejectUnknown(String key,Object value) {
            throw new IllegalArgumentException("Unknown frozen dependency field");
        }
    }
}
