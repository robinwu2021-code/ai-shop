package ai.neargo.commerce.api.catalog;

/** Stable module outcomes; transport adapters map codes rather than parsing messages. */
public enum CatalogErrorCode {
    INVALID_ARGUMENT,
    NOT_FOUND,
    VERSION_CONFLICT,
    FORBIDDEN,
    IMMUTABLE_VERSION
}
