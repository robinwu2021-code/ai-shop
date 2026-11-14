package ai.neargo.shop.spi.settle;

/**
 * Trusted internal cash execution only. The source owner must approve the source execution and durably
 * finish liability reversal before requesting money; this port grants neither permission nor reversal.
 */
public interface OrdinaryRefundPort {
    Receipt request(Execution execution);

    /** Recovery always supplies the original frozen request; there is no bare-key query operation. */
    Receipt query(Execution execution);

    /** Original merchant reference and physical cash payment are separate coordinates and must both match. */
    record Execution(String orderNo, String userNo, String originOutTradeNo, RefundRequest refund) { }

    /** Binding remains present even when the channel outcome is unknown. */
    record Receipt(Execution execution, RefundDispatchResult outcome) { }
}
