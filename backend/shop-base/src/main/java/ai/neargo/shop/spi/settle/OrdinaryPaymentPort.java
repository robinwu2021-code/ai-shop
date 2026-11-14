package ai.neargo.shop.spi.settle;

/** Ordinary PAY ledger access. Opening records an intent; it does not initiate a channel payment. */
public interface OrdinaryPaymentPort {
    Attempt open(SettlePort.PaymentOpen request);

    Lookup query(Reference reference);

    /** PAY owner queries its gateway and settles only an exact successful original cash receipt. */
    Lookup refresh(Reference reference);

    Lookup success(OrderReference reference);

    /** Read-only channel evidence. UNKNOWN is never permission to close an intent. */
    ChannelEvidence queryChannel(Reference reference);

    record Reference(String orderNo, String userNo, String outTradeNo) { }

    record OrderReference(String orderNo, String userNo) { }

    record Lookup(boolean found, Attempt payment) { }

    /** Original reference and ledger facts; missing historical amount/currency are not synthesized. */
    record Attempt(String paymentNo, String outTradeNo, String orderNo, String userNo,
                   String entityNo, String payChannel, String tradeNo, Long amountMinor,
                   String currency, String status, String documentNo) { }

    enum ChannelStatus { UNKNOWN, NOT_FOUND, UNPAID, PAID }

    /** Channel response is evidence, independent from the ledger status and requested amount. */
    record ChannelEvidence(String outTradeNo, ChannelStatus status, Long amountMinor,
                           String currency, String tradeNo) { }
}
