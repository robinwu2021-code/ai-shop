package ai.neargo.commerce.api.catalog;

/** Master and version optimistic-lock tokens are distinct. */
public record PlanVersionView(PlanDraft draft, String status, int revision, long version) {}
