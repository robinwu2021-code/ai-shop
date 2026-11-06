package ai.neargo.commerce.api.catalog;

import java.util.Map;

/**
 * Merchant-mall product category. {@code categoryCode} is immutable once created; {@code
 * parentCode} null means level 1. {@code names} keys are a subset of zh-CN, zh-TW and en.
 */
public record CategoryDraft(
        String categoryCode,
        String parentCode,
        Map<String, String> names,
        String icon,
        int sortOrder,
        boolean enabled,
        long expectedVersion,
        String reason) {
    public CategoryDraft {
        names = names == null ? Map.of() : Map.copyOf(names);
    }
}
