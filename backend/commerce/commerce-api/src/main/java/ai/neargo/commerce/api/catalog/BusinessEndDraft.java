package ai.neargo.commerce.api.catalog;

import ai.neargo.commerce.api.common.*;

public record BusinessEndDraft(String code, String name, boolean systemOnly, boolean enabled, long expectedVersion) {}
