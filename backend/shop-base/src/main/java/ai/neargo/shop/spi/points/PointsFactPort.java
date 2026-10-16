package ai.neargo.shop.spi.points;

import java.time.Instant;
import java.util.List;

/** Durable order facts keep pay independent of order storage and make replay deterministic. */
public interface PointsFactPort {
    /** Consumes an authoritative fact, rejecting conflicting payloads under the same event number. */
    void accept(LifecycleFact fact);

    void acceptEarning(PointsEarnPort.GrantCommand command);
    void acceptRefundConfirmation(RefundConfirmationFact fact);
    record RefundConfirmationFact(String eventNo,String typeCode,String subOrderNo,String userNo,
                                  long sourceVersion,Instant occurredAt,PointsRefundPort.RefundConfirmation confirmation) { }
    void acceptClosure(RefundClosureFact fact);
    void acceptZeroCash(PointsRefundPort.ZeroCashCompletionFact fact);

    enum FactKind { PAID, CANCELLED, REFUND_SUCCEEDED, FINALIZED, REFUND_CLOSED }
    record LineSnapshot(String itemId, long quantity, long goodsMinor, long refundedGoodsMinor) { }
    record LifecycleFact(String eventNo, String payloadHash, String subOrderNo, String userNo,
                         String typeCode, long sourceVersion, FactKind kind, long finalGoodsMinor,
                         long refundedGoodsMinor, List<LineSnapshot> lines, Instant occurredAt) {
        public LifecycleFact { lines = List.copyOf(lines); }
    }
    /** Trusted source proof that all refund responsibility has ended, distinct from order finalization. */
    record RefundClosureFact(String eventNo,String typeCode,String subOrderNo,String userNo,long sourceVersion,
                             Instant windowClosedAt,boolean noOpenAfterSale,boolean fundsFinal,Instant occurredAt) { }
}
