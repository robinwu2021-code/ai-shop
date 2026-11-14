package ai.neargo.commerce.core.catalog;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.*;

class CategoryTest {
    final CatalogFixture f = new CatalogFixture();

    CategoryDraft draft(String code, String parent, boolean enabled, long expected) {
        return new CategoryDraft(
                code, parent, Map.of("zh-TW", code), null, 1, enabled, expected, "ops");
    }

    long version(String code) {
        return f.facade.categories(f.ops).stream()
                .filter(c -> c.categoryCode().equals(code))
                .findFirst()
                .orElseThrow()
                .version();
    }

    static void fails(CatalogErrorCode code, Executable action) {
        assertEquals(code, assertThrows(CatalogException.class, action).code());
    }

    ProductDraft product(String category, long expected) {
        return new ProductDraft(
                "product", category, ProductKind.BUNDLE, "套餐", "d", List.of("RETAIL"), 1,
                expected, "draft");
    }

    @Test
    void multiLevelTreeIsComputedAndOrdered() {
        f.facade.saveCategory(f.ops, draft("hw", null, true, 0));
        f.facade.saveCategory(f.ops, draft("pos", "hw", true, 0));
        f.facade.saveCategory(f.ops, draft("printer", "pos", true, 0));
        f.facade.saveCategory(f.ops, draft("plans", null, true, 0));
        var list = f.facade.categories(f.ops);
        assertEquals(
                List.of("hw", "plans", "pos", "printer"),
                list.stream().map(CategoryView::categoryCode).toList());
        assertEquals(List.of(1, 1, 2, 3), list.stream().map(CategoryView::level).toList());
        assertEquals("ENABLED", list.getFirst().status());
        assertEquals(1, list.getFirst().version());
        assertEquals(list, f.facade.overview(f.ops).categories());
        assertEquals("CATALOG_CATEGORY", f.audits.getLast().aggregateType());
        assertEquals("CATEGORY_SAVED", f.audits.getLast().eventType());
    }

    @Test
    void depthIsCappedAtThreeIncludingMovedSubtrees() {
        f.facade.saveCategory(f.ops, draft("a", null, true, 0));
        f.facade.saveCategory(f.ops, draft("b", "a", true, 0));
        f.facade.saveCategory(f.ops, draft("c", "b", true, 0));
        fails(CatalogErrorCode.CATEGORY_TOO_DEEP, () -> f.facade.saveCategory(f.ops, draft("d", "c", true, 0)));
        f.facade.saveCategory(f.ops, draft("x", null, true, 0));
        f.facade.saveCategory(f.ops, draft("y", "x", true, 0));
        // moving b (with child c) under y would put c at level 4
        fails(CatalogErrorCode.CATEGORY_TOO_DEEP, () -> f.facade.saveCategory(f.ops, draft("b", "y", true, version("b"))));
        // moving c to top level recomputes its level
        f.facade.saveCategory(f.ops, draft("c", null, true, version("c")));
        assertEquals(1, f.facade.categories(f.ops).stream().filter(c -> c.categoryCode().equals("c")).findFirst().orElseThrow().level());
        // now b (no children) may move under y → level 3
        f.facade.saveCategory(f.ops, draft("b", "y", true, version("b")));
    }

    @Test
    void movingAParentRecomputesDescendantLevels() {
        f.facade.saveCategory(f.ops, draft("a", null, true, 0));
        f.facade.saveCategory(f.ops, draft("b", "a", true, 0));
        f.facade.saveCategory(f.ops, draft("c", "b", true, 0));
        f.facade.saveCategory(f.ops, draft("b", null, true, version("b")));
        var levels = new HashMap<String, Integer>();
        f.facade.categories(f.ops).forEach(c -> levels.put(c.categoryCode(), c.level()));
        assertEquals(Map.of("a", 1, "b", 1, "c", 2), levels);
    }

    @Test
    void cyclesAndBadParentsAreRejected() {
        f.facade.saveCategory(f.ops, draft("a", null, true, 0));
        f.facade.saveCategory(f.ops, draft("b", "a", true, 0));
        fails(CatalogErrorCode.CATEGORY_CYCLE, () -> f.facade.saveCategory(f.ops, draft("a", "b", true, version("a"))));
        fails(CatalogErrorCode.CATEGORY_CYCLE, () -> f.facade.saveCategory(f.ops, draft("a", "a", true, version("a"))));
        fails(CatalogErrorCode.CATEGORY_UNAVAILABLE, () -> f.facade.saveCategory(f.ops, draft("z", "nope", true, 0)));
        f.facade.saveCategory(f.ops, draft("off", null, false, 0));
        fails(CatalogErrorCode.CATEGORY_UNAVAILABLE, () -> f.facade.saveCategory(f.ops, draft("z", "off", true, 0)));
        fails(CatalogErrorCode.VERSION_CONFLICT, () -> f.facade.saveCategory(f.ops, draft("a", null, true, 0)));
    }

    @Test
    void namesAndCodeAreValidated() {
        fails(CatalogErrorCode.INVALID_ARGUMENT, () -> f.facade.saveCategory(f.ops, new CategoryDraft("a", null, Map.of(), null, 1, true, 0, "r")));
        fails(CatalogErrorCode.INVALID_ARGUMENT, () -> f.facade.saveCategory(f.ops, new CategoryDraft("a", null, Map.of("fr", "x"), null, 1, true, 0, "r")));
        fails(CatalogErrorCode.INVALID_ARGUMENT, () -> f.facade.saveCategory(f.ops, new CategoryDraft("a", null, Map.of("en", " "), null, 1, true, 0, "r")));
        fails(CatalogErrorCode.INVALID_ARGUMENT, () -> f.facade.saveCategory(f.ops, new CategoryDraft("a b", null, Map.of("en", "x"), null, 1, true, 0, "r")));
        fails(CatalogErrorCode.INVALID_ARGUMENT, () -> f.facade.saveCategory(f.ops, new CategoryDraft("a", null, Map.of("en", "x"), "x".repeat(513), 1, true, 0, "r")));
    }

    @Test
    void disableNeedsNoEnabledChildrenAndNoOnSaleProducts() {
        f.setup();
        f.facade.saveCategory(f.ops, draft("a", null, true, 0));
        f.facade.saveCategory(f.ops, draft("b", "a", true, 0));
        fails(CatalogErrorCode.CATEGORY_HAS_CHILDREN, () -> f.facade.saveCategory(f.ops, draft("a", null, false, version("a"))));
        f.facade.saveCategory(f.ops, draft("b", "a", false, version("b")));
        f.facade.saveCategory(f.ops, draft("a", null, false, version("a")));
        f.facade.saveCategory(f.ops, draft("a", null, true, version("a")));
        // draft product references b, so disabling b is still fine (not on sale)
        f.facade.saveCategory(f.ops, draft("b", "a", true, version("b")));
        f.facade.saveProduct(f.ops, product("b", 0));
        f.facade.saveCategory(f.ops, draft("b", "a", false, version("b")));
    }

    @Test
    void onSaleProductBlocksDisable() {
        f.facade.saveCategory(f.ops, draft("a", null, true, 0));
        var state = f.repo.load("tenant-a", CatalogLockMode.NONE);
        state.products.put("p", new CatalogState.Product(product("a", 0), "ON_SALE", 1));
        f.repo.save("tenant-a", state, state, null);
        fails(CatalogErrorCode.CATEGORY_IN_USE, () -> f.facade.saveCategory(f.ops, draft("a", null, false, version("a"))));
    }

    @Test
    void productNeedsAnEnabledCategoryOfAnyLevel() {
        f.setup();
        fails(CatalogErrorCode.CATEGORY_UNAVAILABLE, () -> f.facade.saveProduct(f.ops, product("missing", 0)));
        f.facade.saveCategory(f.ops, draft("a", null, true, 0));
        f.facade.saveCategory(f.ops, draft("b", "a", true, 0));
        f.facade.saveCategory(f.ops, draft("c", "b", false, 0));
        fails(CatalogErrorCode.CATEGORY_UNAVAILABLE, () -> f.facade.saveProduct(f.ops, product("c", 0)));
        f.facade.saveProduct(f.ops, product("b", 0));
    }

    @Test
    void writesNeedCatalogEdit() {
        f.capabilities.remove("commerce:catalog:edit");
        fails(CatalogErrorCode.FORBIDDEN, () -> f.facade.saveCategory(f.ops, draft("a", null, true, 0)));
        f.capabilities.remove("commerce:catalog:read");
        fails(CatalogErrorCode.FORBIDDEN, () -> f.facade.categories(f.ops));
    }
}
