package ai.neargo.shop.food.policy;

import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSkuPriceInput;
import ai.neargo.shop.food.dto.PublicationMarketContext;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class CurrencyMarketFixtures {
    static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    static final MarketScope SCOPE = new MarketScope("ENTITY", "STORE");
    private CurrencyMarketFixtures() { }

    static MarketFacts facts(String market, String currency, int scale, Instant at) {
        return CatalogPriceSourceFacts.sealMarket(new MarketFacts(1, "CATALOG_MARKET", SCOPE,
                new Market(market, currency, scale), at, at.plusSeconds(30), null));
    }

    static PublicationMarketContext context(String market, String currency, int scale) {
        return PublicationMarketPolicy.capture(SCOPE, facts(market, currency, scale, NOW), NOW);
    }

    static Object upgrade(String kind, int schema, PublicationMarketContext context) {
        var old = CurrencyPublicationFixtures.legacy(kind);
        if (old instanceof PublicationSnapshot v) return new PublicationSnapshot(schema, v.configKind(), v.configNo(),
                v.sourceVersion(), v.modifier(), v.optionGroup(), v.dependencies(), v.skuFacts(), v.specFacts(),
                v.approvalRef(), context);
        if (old instanceof BundlePublicationSnapshot v) return new BundlePublicationSnapshot(schema, v.configKind(),
                v.configNo(), v.sourceVersion(), v.bundle(), v.skuFacts(), v.approvalRef(), context);
        if (old instanceof RecipePublicationSnapshot v) return new RecipePublicationSnapshot(schema,
                v.configKind(),
                v.configNo(), v.sourceVersion(), v.recipe(), v.skuFacts(), v.materialFacts(),
                v.optionGroups(),
                v.approvalRef(), context);
        if (old instanceof MenuPublicationSnapshot) {
            return menu(schema, context, List.of(), List.of(), null);
        }
        throw new IllegalArgumentException(kind);
    }

    static MenuPublicationSnapshot menu(int schema, PublicationMarketContext context,
            List<MenuPublicationSnapshot.Dependency> dependencies, List<String> groups,
            List<MenuSkuPriceInput> prices) {
        var old = CurrencyPublicationFixtures.menu();
        var c = old.menu();
        var item = new MenuItemInput("ITEM", "GOODS", 0, true, "EXPLICIT", List.of("SKU"), "REPLACE", groups,
                List.of(), prices);
        var definition = new MenuConfigResultVO(c.definition().config(), c.definition().schedule(),
                new MenuCompositionVO(List.of(new MenuCategoryInput("CATEGORY", "Menu", 0, List.of(item)))),
                List.of(new MenuContextInput("CHANNEL", "DELIVERY", "BINDING", "MENU_PUBLISHED_PRICES_V1")));
        var captured = new MenuPublicationSnapshot.Captured(c.entityNo(), c.storeNo(), definition, c.dishes(),
                List.of(new MenuPublicationSnapshot.ItemOptions("ITEM", groups)), dependencies,
                List.of(new MenuChannelBindingVO("BINDING", "MENU", "STORE", "keeta", "ACCOUNT", "EXTERNAL",
                        "ACTIVE", 1L)));
        var skus = new ArrayList<SkuIdentityFact>(old.skuFacts());
        if (dependencies.stream().anyMatch(d -> "BUNDLE".equals(d.configKind()))) {
            skus.add(new SkuIdentityFact("BUNDLE", "GOODS", "ENTITY", "STORE", 3));
        }
        return new MenuPublicationSnapshot(schema, "MENU", "MENU", 0, captured, skus, old.goodsFacts(), null, context);
    }

    static MenuPublicationSnapshot.Dependency dependency(String kind, PublicationMarketContext context) {
        String document = CurrencyPublicationFixtures.freeze(upgrade(kind, 6, context));
        return new MenuPublicationSnapshot.Dependency("REV_" + kind, kind, CurrencyPublicationFixtures.number(kind), 1,
                6, PublicationDocuments.hash(CurrencyPublicationFixtures.JSON, document), document);
    }
}
