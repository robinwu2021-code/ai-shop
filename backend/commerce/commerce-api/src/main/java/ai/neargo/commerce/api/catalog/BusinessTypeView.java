package ai.neargo.commerce.api.catalog;

/** Master and version optimistic-lock tokens are distinct. */
public record BusinessTypeView(BusinessTypeDraft draft, long version) {}
