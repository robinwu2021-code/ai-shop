package ai.neargo.shop.event.outbox;

/** An explicitly retryable channel rejection, unlike a request whose external outcome is unknown. */
public final class RetryableOutboxException extends IllegalStateException {
    public RetryableOutboxException(String message) { super(message); }
}
