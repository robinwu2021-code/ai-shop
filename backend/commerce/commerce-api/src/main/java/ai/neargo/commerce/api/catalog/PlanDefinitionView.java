package ai.neargo.commerce.api.catalog;

/** Master and version optimistic-lock tokens are distinct. */
public record PlanDefinitionView(
        String planNo,
        String typeCode,
        ai.neargo.commerce.api.common.PlanTier tier,
        String currentVersionNo,
        String saleStatus,
        long version) {}
