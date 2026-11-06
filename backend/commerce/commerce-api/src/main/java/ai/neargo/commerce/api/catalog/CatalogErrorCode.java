package ai.neargo.commerce.api.catalog;

/** Stable module outcomes; transport adapters map codes rather than parsing messages. */
public enum CatalogErrorCode {
    INVALID_ARGUMENT,
    NOT_FOUND,
    VERSION_CONFLICT,
    FORBIDDEN,
    IMMUTABLE_VERSION,
    /** Category would sit deeper than level 3 (itself or its moved subtree). */
    CATEGORY_TOO_DEEP,
    /** Category parent is itself or one of its descendants. */
    CATEGORY_CYCLE,
    /** Category still has enabled children, so it cannot be disabled. */
    CATEGORY_HAS_CHILDREN,
    /** Category is referenced by an on-sale product, so it cannot be disabled. */
    CATEGORY_IN_USE,
    /** Product category (or new category parent) is missing or disabled. */
    CATEGORY_UNAVAILABLE
}
