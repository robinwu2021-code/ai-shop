package ai.neargo.commerce.api.catalog;

/** JDK-only module failure. Infrastructure failures remain infrastructure failures. */
public final class CatalogException extends RuntimeException {
    private final CatalogErrorCode code;

    public CatalogException(CatalogErrorCode code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code);
    }

    public CatalogErrorCode code() {
        return code;
    }
}
