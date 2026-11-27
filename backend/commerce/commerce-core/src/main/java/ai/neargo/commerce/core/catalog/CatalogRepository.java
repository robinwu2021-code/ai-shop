package ai.neargo.commerce.core.catalog;

/**
 * load(SHARED/EXCLUSIVE) and save require the identical local UoW transaction. All current reads
 * lock tenant rows in a stable order. save persists only differences with before, using version
 * predicates; it must never rewrite unchanged rows.
 */
public interface CatalogRepository {
    default CatalogChangeJournal changeJournal() { return null; }

    CatalogState load(String tenantNo, CatalogLockMode mode);

    default java.util.Collection<CatalogState.Category> loadCategories(String tenantNo) {
        return load(tenantNo, CatalogLockMode.SHARED).categories.values();
    }

    default java.util.Collection<CatalogState.Type> businessTypes(String tenantNo) {
        return load(tenantNo, CatalogLockMode.NONE).types.values();
    }

    default CatalogState overview(String tenantNo, java.util.function.LongSupplier clock) {
        return load(tenantNo, CatalogLockMode.SHARED);
    }

    default CatalogState lockForWrite(String tenantNo, java.util.function.LongSupplier clock) {
        return load(tenantNo, CatalogLockMode.EXCLUSIVE);
    }

    default CatalogState lockCategoryTree(String tenantNo) {
        return load(tenantNo, CatalogLockMode.EXCLUSIVE);
    }

    default boolean hasOnSaleProduct(String tenantNo, String category, CatalogState state) {
        return state.products.values().stream().anyMatch(product ->
                "ON_SALE".equals(product.saleStatus()) && category.equals(product.draft().categoryCode()));
    }

    default void saveBusinessType(String tenantNo, CatalogState.Type before, CatalogState.Type after,
            CatalogWriteContext context) {
        throw new UnsupportedOperationException("Dedicated business type dictionary required");
    }

    void save(
            String tenantNo, CatalogState before, CatalogState after, CatalogWriteContext context);
}
