package ai.neargo.commerce.api.catalog;

/**
 * Master and version optimistic-lock tokens are distinct. {@code catalogVersion} is the token an
 * industry publication must echo as {@code expectedCatalogVersion} (bumped by each publication).
 */
public record BusinessTypeView(BusinessTypeDraft draft, long version, long catalogVersion) {}
