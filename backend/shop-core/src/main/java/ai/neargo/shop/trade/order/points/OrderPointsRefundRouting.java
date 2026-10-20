package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.spi.points.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Routing is pinned by the admitted order snapshot, never a mutable rollout flag. */
@Service
public class OrderPointsRefundRouting {
    private final OrderPointsRefundCoordinator refunds;
    private final OrderPointsBindingService orders;
    private final org.springframework.beans.factory.ObjectProvider<PointsOperationsPort> operations;
    private final ObjectMapper json=new ObjectMapper();
    public OrderPointsRefundRouting(OrderPointsRefundCoordinator refunds,OrderPointsBindingService orders,
            org.springframework.beans.factory.ObjectProvider<PointsOperationsPort> operations) {this.refunds=refunds;this.orders=orders;this.operations=operations;}
    public PointsRefundPort route(String afterSale,PointsRefundPort legacy) {
        var refund=refunds.inspect(afterSale);
        if(refund==null)return legacy;
        var order=orders.find(refund.subOrderNo());
        if(order==null)return legacy;
        var frozen=json.readValue(order.snapshot().ruleSnapshot(),PointsOrderPlacementCoordinator.EarningSnapshot.class);
        if(frozen.schemaVersion()<3)return legacy;
        if(frozen.schemaVersion()!=3)throw new IllegalArgumentException("POINTS_EARNING_SCHEMA_UNSUPPORTED");
        var port=operations.getIfAvailable();if(port==null)throw new IllegalArgumentException("POINTS_IMPLEMENTATION_REQUIRED");
        var command=refund.command();
        return new PointsRefundPort() {
            private PointsOperationsPort.Context context(String action) {
                return new PointsOperationsPort.Context("TRADE:REFUND:"+action+":"+afterSale,
                    new PointsOperationsPort.Operation(PointsOperationsPort.Source.TRADE,afterSale,"ORDER_REFUND_V3",command.typeCode()),command.currency());
            }
            public RefundPreparation prepare(RefundCommand c) {return port.prepareRefund(context("PREPARE"),c);}
            public CashDecision authorizeCash(String no,String hash,long revision) {return port.authorizeRefund(context("AUTHORIZE"),new PointsOperationsPort.RefundAuthorization(no,hash,revision));}
            public RefundOutcome confirm(RefundConfirmation c) {return port.confirmRefund(context("CONFIRM"),c);}
            public void requestCompensation(RefundAbort c,long revision) {port.compensateRefund(context("COMPENSATE"),c,revision);}
            public void abort(RefundAbort c) {port.abortRefund(context("ABORT"),c);}
            public RefundRecoveryStatus recoveryStatus(String no,String hash) {return port.refundStatus(context("STATUS"),no,hash);}
        };
    }
}
