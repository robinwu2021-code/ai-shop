package ai.neargo.commerce.api.catalog;

public record BusinessTypeDraft(
        String typeCode,
        String name,
        int sortOrder,
        boolean enabled,
        long expectedVersion,
        String reason) {}
