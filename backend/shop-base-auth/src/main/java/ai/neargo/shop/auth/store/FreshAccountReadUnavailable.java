package ai.neargo.shop.auth.store;

/**
 * Host HTTP admission maps infrastructure uncertainty to CHECK_UNAVAILABLE, never account invalid.
 */
public final class FreshAccountReadUnavailable extends RuntimeException {
    public FreshAccountReadUnavailable() {
        super("CHECK_UNAVAILABLE");
    }
}
