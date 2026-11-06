package ai.neargo.commerce.core.mall;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.mall.StorefrontView;
import ai.neargo.commerce.core.catalog.CatalogState;

import org.junit.jupiter.api.Test;

import java.util.*;

class StorefrontCategoriesTest {
    static void put(CatalogState s, String code, String parent, int level, int sort, boolean on) {
        s.categories.put(
                code,
                new CatalogState.Category(
                        new CategoryDraft(code, parent, Map.of("en", code), null, sort, on, 0, "r"),
                        level,
                        1));
    }

    static StorefrontView.Product product(String category) {
        return new StorefrontView.Product(
                new ProductDraft("p-" + category, category, ProductKind.BUNDLE, "n", "d",
                        List.of("RETAIL"), 0, 0, "r"),
                List.of());
    }

    @Test
    void onlyUsedCategoriesAndTheirAncestorsInTreeOrder() {
        var s = new CatalogState();
        put(s, "hw", null, 1, 2, true);
        put(s, "pos", "hw", 2, 1, true);
        put(s, "printer", "pos", 3, 1, true);
        put(s, "plans", null, 1, 1, true);
        put(s, "unused", null, 1, 0, true);
        put(s, "off", null, 1, 0, false);
        var out =
                DefaultStorefrontFacade.categories(
                        s, List.of(product("printer"), product("plans"), product("pos"), product("off"), product("ghost")));
        assertEquals(
                List.of("plans", "hw", "pos", "printer"),
                out.stream().map(StorefrontView.Category::code).toList());
        assertEquals("pos", out.getLast().parentCode());
        assertEquals(3, out.getLast().level());
    }
}
