package ai.neargo.shop.event.outbox;

/** Same source identity with different content requires an operator decision. */
public final class OutboxSourceConflictException extends IllegalStateException {
    public OutboxSourceConflictException(Throwable cause) { super("SOURCE_EVENT_CONFLICT", cause); }
}
