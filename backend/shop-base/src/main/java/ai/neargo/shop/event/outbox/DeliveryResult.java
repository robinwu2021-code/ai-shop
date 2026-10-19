package ai.neargo.shop.event.outbox;

/** External timeouts with unqueryable results cannot be blindly retried. */
public record DeliveryResult(Kind kind, String errorCode, String detail) {
    public enum Kind { SENT, WARNING, RETRYABLE, PERMANENT, OUTCOME_UNKNOWN }
    public DeliveryResult {
        if (kind == null) { throw new IllegalArgumentException("Result kind required"); }
    }
    public static DeliveryResult sent() { return new DeliveryResult(Kind.SENT, null, null); }
    public static DeliveryResult warning(String detail) {
        return new DeliveryResult(Kind.WARNING, "CHANNEL_WARNING", detail);
    }
    public static DeliveryResult retry(String code, String detail) {
        return new DeliveryResult(Kind.RETRYABLE, code, detail);
    }
    public static DeliveryResult failed(String code, String detail) {
        return new DeliveryResult(Kind.PERMANENT, code, detail);
    }
    public static DeliveryResult unknown(String detail) {
        return new DeliveryResult(Kind.OUTCOME_UNKNOWN, "OUTCOME_UNKNOWN", detail);
    }
}
