package ai.neargo.shop.pay.points.port;

import ai.neargo.shop.pay.points.event.PointsEventQueue;
import ai.neargo.shop.pay.points.event.PointsEventQueue.Kind;
import ai.neargo.shop.pay.points.event.PointsEventQueue.Message;
import ai.neargo.shop.pay.points.policy.PointsDigests;
import ai.neargo.shop.spi.points.PointsEarnPort.GrantCommand;
import ai.neargo.shop.spi.points.PointsFactPort;
import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashCompletionFact;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Trusted source integration only: Ops cannot manufacture source or bank facts through this SPI.
 */
@Component
public class PointsFactPortImpl implements PointsFactPort {
    private final PointsEventQueue queue;
    private final ObjectMapper json = new ObjectMapper();

    public PointsFactPortImpl(PointsEventQueue queue) {
        this.queue = queue;
    }

    @Override
    public void accept(LifecycleFact fact) {
        require(
                fact != null
                        && fact.kind() != null
                        && fact.eventNo() != null
                        && fact.sourceVersion() >= 0
                        && fact.occurredAt() != null
                        && fact.userNo() != null
                        && fact.subOrderNo() != null,
                "POINTS_FACT_INVALID");
        require(
                fact.kind() == FactKind.PAID
                        || fact.kind() == FactKind.FINALIZED
                        || fact.kind() == FactKind.CANCELLED,
                "POINTS_TYPED_PROOF_REQUIRED");
        require(digest(fact).equals(fact.payloadHash()), "POINTS_FACT_HASH_MISMATCH");
        queue.enqueue(
                fact.typeCode(),
                "SOURCE:" + fact.eventNo(),
                new Message(Kind.SOURCE_FACT, json.writeValueAsString(fact)));
    }

    /**
     * SHA-256 of the serialized record with payloadHash=null; field and line order form the source
     * contract.
     */
    public static String digest(LifecycleFact fact) {
        var unsigned =
                new LifecycleFact(
                        fact.eventNo(),
                        null,
                        fact.subOrderNo(),
                        fact.userNo(),
                        fact.typeCode(),
                        fact.sourceVersion(),
                        fact.kind(),
                        fact.finalGoodsMinor(),
                        fact.refundedGoodsMinor(),
                        fact.lines(),
                        fact.occurredAt());
        return PointsDigests.sha256(new ObjectMapper().writeValueAsString(unsigned));
    }

    @Override
    public void acceptEarning(GrantCommand command) {
        require(command != null, "POINTS_GRANT_INPUT_INVALID");
        queue.enqueue(
                command.typeCode(),
                "SOURCE:EARN:" + command.eventNo(),
                new Message(Kind.EARN_GRANT, json.writeValueAsString(command)));
    }

    @Override
    public void acceptRefundConfirmation(RefundConfirmationFact fact) {
        require(fact != null, "POINTS_REFUND_FACT_INVALID");
        queue.enqueue(
                fact.typeCode(),
                "SOURCE:" + fact.eventNo(),
                new Message(Kind.REFUND_CONFIRMATION, json.writeValueAsString(fact)));
    }

    @Override
    public void acceptClosure(RefundClosureFact fact) {
        require(fact != null, "POINTS_CLOSURE_PROOF_INVALID");
        queue.enqueue(
                fact.typeCode(),
                "SOURCE:" + fact.eventNo(),
                new Message(Kind.REFUND_CLOSURE, json.writeValueAsString(fact)));
    }

    @Override
    public void acceptZeroCash(ZeroCashCompletionFact fact) {
        require(fact != null, "POINTS_ZERO_CASH_FACT_INVALID");
        queue.enqueue(
                fact.typeCode(),
                "SOURCE:" + fact.eventNo(),
                new Message(Kind.ZERO_CASH, json.writeValueAsString(fact)));
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
