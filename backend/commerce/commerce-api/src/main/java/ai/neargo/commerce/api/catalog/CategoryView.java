package ai.neargo.commerce.api.catalog;

import java.util.Map;

/** Flat category row; {@code level} is 1..3 and computed by the server from the parent chain. */
public record CategoryView(
        String categoryCode,
        String parentCode,
        int level,
        Map<String, String> names,
        String icon,
        int sortOrder,
        String status,
        long version) {}
