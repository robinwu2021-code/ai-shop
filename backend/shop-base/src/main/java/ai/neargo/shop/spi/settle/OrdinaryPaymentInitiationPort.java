package ai.neargo.shop.spi.settle;

/** Actual ordinary initiation; payer identity is resolved by authenticated core, never a buyer payload. */
public interface OrdinaryPaymentInitiationPort {
    SettlePort.PayInitResult initiate(SettlePort.PaymentOpen request);
}
