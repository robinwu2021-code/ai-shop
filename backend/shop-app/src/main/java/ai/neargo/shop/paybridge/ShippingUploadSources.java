package ai.neargo.shop.paybridge;

/** Read adapter captures actual per-child/package facts for a payment-wide upload without holding order locks. */
public interface ShippingUploadSources {
    ShippingUploadSnapshot snapshotOf(String orderNo);
    /** Durable refund events address a child; resolve its original payment only after confirmed refund. */
    default String paymentOfRefundedChild(String subOrderNo) { return null; }
}
