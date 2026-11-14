package ai.neargo.commerce.core.catalog;

/**
 * load(SHARED/EXCLUSIVE) and save require the identical local UoW transaction. All current reads
 * lock tenant rows in a stable order. save persists only differences with before, using version
 * predicates; it must never rewrite unchanged rows.
 */
public interface CatalogRepository {
    CatalogState load(String tenantNo, CatalogLockMode mode);

    void save(
            String tenantNo, CatalogState before, CatalogState after, CatalogWriteContext context);
}
