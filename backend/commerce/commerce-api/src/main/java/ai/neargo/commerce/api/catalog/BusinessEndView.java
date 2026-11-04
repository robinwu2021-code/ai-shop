package ai.neargo.commerce.api.catalog;

/** Master and version optimistic-lock tokens are distinct. */
public record BusinessEndView(BusinessEndDraft draft, long version) {}
