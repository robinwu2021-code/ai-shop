package ai.neargo.commerce.core.catalog.category;

import static ai.neargo.commerce.core.catalog.CatalogSupport.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.catalog.*;

import java.util.*;

/**
 * Merchant-mall category tree (max {@link #MAX_DEPTH} levels). Same snapshot/lock/audit boundary as
 * products, so the product category check and category disable see one consistent catalogue.
 */
public final class CategoryService {
    public static final int MAX_DEPTH = 3;
    static final Set<String> LOCALES = Set.of("zh-CN", "zh-TW", "en");
    static final Comparator<CatalogState.Category> ORDER =
            Comparator.<CatalogState.Category>comparingInt(CatalogState.Category::level)
                    .thenComparingInt(c -> c.draft().sortOrder())
                    .thenComparing(c -> c.draft().categoryCode());

    private final CatalogSupport s;

    public CategoryService(CatalogSupport s) {
        this.s = s;
    }

    public List<CategoryView> list(ActorContext a) {
        return views(s.read(a));
    }

    public static List<CategoryView> views(CatalogState state) {
        return state.categories.values().stream().sorted(ORDER).map(CategoryService::view).toList();
    }

    static CategoryView view(CatalogState.Category c) {
        var d = c.draft();
        return new CategoryView(
                d.categoryCode(),
                d.parentCode(),
                c.level(),
                new TreeMap<>(d.names()),
                d.icon(),
                d.sortOrder(),
                c.status(),
                c.version());
    }

    public DraftSaveResult save(ActorContext a, CategoryDraft d) {
        String code = code(d.categoryCode(), 32);
        if (d.parentCode() != null) code(d.parentCode(), 32);
        check(!d.names().isEmpty(), "names required");
        d.names()
                .forEach(
                        (locale, name) -> {
                            check(LOCALES.contains(locale), "unsupported locale " + locale);
                            text(name, 64, "name");
                        });
        check(d.icon() == null || (!d.icon().isBlank() && d.icon().length() <= 512), "icon max 512");
        return s.write(
                a,
                "edit",
                d.reason(),
                "CATEGORY",
                code,
                "CATEGORY_SAVED",
                null,
                state -> {
                    var old = state.categories.get(code);
                    expected(d.expectedVersion(), old == null ? 0 : old.version());
                    int level = 1;
                    if (d.parentCode() != null) {
                        if (d.parentCode().equals(code) || isDescendant(state, d.parentCode(), code))
                            fail(CatalogErrorCode.CATEGORY_CYCLE, "parent inside own subtree");
                        var parent = state.categories.get(d.parentCode());
                        if (parent == null || !parent.draft().enabled())
                            fail(
                                    CatalogErrorCode.CATEGORY_UNAVAILABLE,
                                    "parent category missing or disabled");
                        level = parent.level() + 1;
                    }
                    if (level - 1 + height(state, code) > MAX_DEPTH)
                        fail(CatalogErrorCode.CATEGORY_TOO_DEEP, "category deeper than " + MAX_DEPTH);
                    if (!d.enabled() && (old == null || old.draft().enabled())) {
                        if (state.categories.values().stream()
                                .anyMatch(
                                        c ->
                                                code.equals(c.draft().parentCode())
                                                        && c.draft().enabled()))
                            fail(CatalogErrorCode.CATEGORY_HAS_CHILDREN, "enabled children remain");
                        if (state.products.values().stream()
                                .anyMatch(
                                        p ->
                                                "ON_SALE".equals(p.saleStatus())
                                                        && code.equals(p.draft().categoryCode())))
                            fail(CatalogErrorCode.CATEGORY_IN_USE, "on-sale products use category");
                    }
                    long version = old == null ? 1 : old.version() + 1;
                    state.categories.put(code, new CatalogState.Category(d, level, version));
                    if (old != null && old.level() != level) relevel(state, code, level);
                    return new DraftSaveResult(code, null, version);
                });
    }

    /** Whether {@code candidate} sits below {@code root}. */
    private static boolean isDescendant(CatalogState state, String candidate, String root) {
        var seen = new HashSet<String>();
        for (var c = state.categories.get(candidate);
                c != null && c.draft().parentCode() != null && seen.add(c.draft().categoryCode());
                c = state.categories.get(c.draft().parentCode()))
            if (c.draft().parentCode().equals(root)) return true;
        return false;
    }

    /** Levels in the subtree rooted at {@code code}, itself included (1 for a leaf/new node). */
    private static int height(CatalogState state, String code) {
        int max = 0;
        for (var c : state.categories.values())
            if (code.equals(c.draft().parentCode()))
                max = Math.max(max, height(state, c.draft().categoryCode()));
        return max + 1;
    }

    /** Children rows change level only; their own version token moves with the write. */
    private static void relevel(CatalogState state, String parent, int parentLevel) {
        for (var c : List.copyOf(state.categories.values()))
            if (parent.equals(c.draft().parentCode())) {
                var code = c.draft().categoryCode();
                state.categories.put(
                        code, new CatalogState.Category(c.draft(), parentLevel + 1, c.version() + 1));
                relevel(state, code, parentLevel + 1);
            }
    }
}
