package ai.neargo.commerce.core.catalog;

/** Shared current reads support READ COMMITTED and real READ ONLY host transactions. */
public enum CatalogLockMode {
    NONE,
    SHARED,
    EXCLUSIVE
}
