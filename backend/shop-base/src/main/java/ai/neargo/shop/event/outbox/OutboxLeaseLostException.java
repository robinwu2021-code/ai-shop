package ai.neargo.shop.event.outbox;

/** Must escape best-effort channel catches, so a stale worker stops issuing requests. */
public final class OutboxLeaseLostException extends IllegalStateException {
    public OutboxLeaseLostException() { super("OUTBOX_LEASE_LOST"); }
}
