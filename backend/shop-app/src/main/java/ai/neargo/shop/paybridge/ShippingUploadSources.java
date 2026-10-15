package ai.neargo.shop.paybridge;

/** Read adapter captures actual per-child/package facts for a payment-wide upload without holding order locks. */
public interface ShippingUploadSources {
    ShippingUploadSnapshot snapshotOf(String orderNo);
}
