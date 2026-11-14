package ai.neargo.commerce.api.port;

/**
 * Immutable inbound identity was reused with different facts. Custom adapters must use this typed
 * conflict.
 */
public final class InboundFactConflictException extends IllegalStateException {
    public InboundFactConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
