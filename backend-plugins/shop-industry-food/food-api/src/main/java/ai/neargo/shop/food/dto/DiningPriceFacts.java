package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.product.DishOfferFacts;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.user.StoreCardFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Internal source evidence only, not a customer quote, tax result, reservation or payment receipt. */
public record DiningPriceFacts(
        @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long schemaVersion,
        Payload payload,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceHash) {
    @JsonAnySetter public void unknown(String key, Object value) { reject(); }

    public record Payload(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String subjectNo,
            CartVO cart, List<MenuRef> menus, List<SkuIdentityFact> identities,
            List<Offer> offers, List<Restriction> restrictions,
            List<Unit> units, Composition composition,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String currency,
            @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor) {
        public Payload {
            menus = menus == null ? null : List.copyOf(menus);
            identities = identities == null ? null : List.copyOf(identities);
            offers = offers == null ? null : List.copyOf(offers);
            restrictions = restrictions == null ? null : List.copyOf(restrictions);
            units = units == null ? null : List.copyOf(units);
        }
        @JsonAnySetter public void unknown(String key, Object value) { reject(); }
    }
    /** Clock-free selected catalog projection permits the receiver to rebuild every charge component. */
    public record Composition(List<CatalogMenuVO> menus, List<CatalogItemVO> items) {
        public Composition {
            menus = menus == null ? null : List.copyOf(menus);
            items = items == null ? null : List.copyOf(items);
        }
        @JsonAnySetter public void unknown(String key, Object value) { reject(); }
    }
    public record Unit(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceLineNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String componentRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String kind,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String goodsNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String priceRevision,
            @JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal quantity,
            @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long unitPriceMinor,
            @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor) {
        @JsonAnySetter public void unknown(String key, Object value) { reject(); }
    }
    public record MenuRef(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo,
            @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long revision,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String contentHash,
            List<DependencyRef> dependencies) {
        public MenuRef { dependencies = dependencies == null ? null : List.copyOf(dependencies); }
        @JsonAnySetter public void unknown(String key, Object value) { reject(); }
    }
    public record DependencyRef(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String kind,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String configNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String revisionNo,
            @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) long revision,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String contentHash) {
        @JsonAnySetter public void unknown(String key, Object value) { reject(); }
    }
    public record Restriction(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo,
            @JsonDeserialize(using = StoreCardFacts.BooleanDeserializer.class) Boolean blocked,
            @JsonDeserialize(using = SalesRestrictionPolicy.DecimalDeserializer.class) BigDecimal remainingQuota,
            @JsonDeserialize(using = StoreCardFacts.BooleanDeserializer.class) Boolean originalStockKnown,
            List<SalesRestrictionVO> rules) {
        public Restriction { rules = rules == null ? null : List.copyOf(rules); }
        @JsonAnySetter public void unknown(String key, Object value) { reject(); }
    }
    private static void reject() { throw new IllegalArgumentException("Unknown source-facts field"); }
}
