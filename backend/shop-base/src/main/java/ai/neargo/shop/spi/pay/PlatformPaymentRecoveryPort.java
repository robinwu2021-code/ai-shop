package ai.neargo.shop.spi.pay;

/** Ledger-classified platform payment/refund recovery, independent of any current login. */
@FunctionalInterface
public interface PlatformPaymentRecoveryPort {
    boolean recover(String paymentNoOrOutTradeNo);
}
