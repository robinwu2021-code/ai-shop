package ai.neargo.commerce.api.subscription;

/** Creation requires an enabled, published business type in this tenant. */
public final class BusinessTypeUnavailableException extends IllegalArgumentException {
    public BusinessTypeUnavailableException(String type) {
        super("Business type unavailable: " + type);
    }
}
