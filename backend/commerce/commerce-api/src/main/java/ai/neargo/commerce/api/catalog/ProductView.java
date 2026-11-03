package ai.neargo.commerce.api.catalog;

/** Master and version optimistic-lock tokens are distinct. */
public record ProductView(ProductDraft draft, String saleStatus, long version) {}
