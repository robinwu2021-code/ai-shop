package ai.neargo.commerce.api.catalog;

import ai.neargo.commerce.api.common.*;

public record BusinessTypeDraft(String typeCode, String name, int sortOrder, boolean enabled, long expectedVersion) {}
