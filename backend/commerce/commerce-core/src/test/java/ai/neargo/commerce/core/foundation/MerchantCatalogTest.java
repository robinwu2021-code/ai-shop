package ai.neargo.commerce.core.foundation;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.catalog.CatalogState;

import org.junit.jupiter.api.Test;

import java.util.*;

class MerchantCatalogTest {
    @Test
    void onlyCurrentExactVersionsAndOnSaleSinglePlanPricesAreDisplayable() {
        var state = new CatalogState();
        state.types.put(
                "R",
                new CatalogState.Type(
                        new BusinessTypeDraft("R", "Retail", 1, true, 4, "private"), 3, 4));
        for (var tier : PlanTier.values()) {
            var key = tier.name();
            state.plans.put(key, new CatalogState.Plan(key, "R", tier, key + "2", "ON_SALE", 8));
            for (var version : List.of("1", "2", "3")) {
                var draft =
                        new PlanDraft(
                                key,
                                key + version,
                                "R",
                                tier,
                                key + version,
                                "summary",
                                List.of("BASE"),
                                "private policy",
                                9,
                                "private");
                state.planVersions.put(
                        key + version,
                        new CatalogState.PlanVersion(
                                draft,
                                1,
                                "3".equals(version) ? "DRAFT" : "PUBLISHED",
                                9,
                                42L,
                                "operator"));
            }
        }
        sku(state, "annual", ProductKind.PLAN, "PRO2", "ON_SALE", "R");
        sku(state, "obsolete", ProductKind.PLAN, "PRO1", "ON_SALE", "R");
        sku(state, "draft", ProductKind.PLAN, "PRO3", "ON_SALE", "R");
        sku(state, "off", ProductKind.PLAN, "PRO2", "OFF_SALE", "R");
        sku(state, "bundle", ProductKind.BUNDLE, "PRO2", "ON_SALE", "R");
        sku(state, "foreign", ProductKind.PLAN, "PRO2", "ON_SALE", "F");
        var result = MerchantCatalog.plans(state, "R");
        assertEquals(
                List.of("FREE2", "PRO2", "CHAIN2"),
                result.plans().stream().map(p -> p.planVersionNo()).toList());
        var pro = result.plans().get(1);
        assertFalse(pro.purchaseAvailable());
        assertEquals(List.of("annual"), pro.priceOptions().stream().map(p -> p.skuNo()).toList());
        assertEquals(new Money("CNY", 1234), pro.priceOptions().getFirst().price());
        assertEquals("UNAVAILABLE", MerchantCatalog.plans(state, "F").status());
        state.plans.put(
                "PRO", new CatalogState.Plan("PRO", "R", PlanTier.PRO, "PRO2", "OFF_SALE", 9));
        assertEquals(3, MerchantCatalog.plans(state, "R").plans().size());
        assertTrue(MerchantCatalog.plans(state, "R").plans().get(1).priceOptions().isEmpty());
        state.plans.remove("CHAIN");
        assertTrue(MerchantCatalog.types(state).isEmpty());
        assertTrue(MerchantCatalog.plans(state, "R").plans().isEmpty());
    }

    static void sku(
            CatalogState state,
            String no,
            ProductKind kind,
            String version,
            String sale,
            String scope) {
        state.products.put(
                no,
                new CatalogState.Product(
                        new ProductDraft(no, "P", kind, no, "", List.of(scope), 0, 1, "private"),
                        "ON_SALE",
                        1));
        state.skus.put(no, new CatalogState.Sku(no, no, no, 1, no + "v", sale, 1));
        var component =
                new SkuComponent(
                        no,
                        ComponentKind.PLAN,
                        version,
                        null,
                        null,
                        365,
                        1,
                        1,
                        false,
                        "ON_FULFILLMENT");
        var draft =
                new SkuDraft(
                        no,
                        no,
                        no + "v",
                        no,
                        new Money("CNY", 2000),
                        new Money("CNY", 1234),
                        List.of(scope),
                        "private",
                        List.of(component),
                        1,
                        8,
                        "private");
        state.skuVersions.put(no + "v", new CatalogState.SkuVersion(draft, 1, "PUBLISHED", 8, 4L));
    }
}
