package ai.neargo.shop.trade.order.points.port;

import ai.neargo.shop.spi.points.PointsOperationsPort;
import ai.neargo.shop.spi.points.PointsRefundPort;

/** Pins each routed refund call to the original order command and after-sale context. */
public final class OrderPointsRefundPort implements PointsRefundPort {
    private final PointsOperationsPort port;
    private final String afterSale;
    private final RefundCommand command;

    public OrderPointsRefundPort(PointsOperationsPort port, String afterSale, RefundCommand command) {
        this.port = port;
        this.afterSale = afterSale;
        this.command = command;
    }

    private PointsOperationsPort.Context context(String action) {
        return new PointsOperationsPort.Context("TRADE:REFUND:"+action+":"+afterSale,
                new PointsOperationsPort.Operation(PointsOperationsPort.Source.TRADE,afterSale,
                        "ORDER_REFUND_V3",command.typeCode()),command.currency());
    }

    @Override
    public RefundPreparation prepare(RefundCommand c) {
        return port.prepareRefund(context("PREPARE"),c);
    }

    @Override
    public CashDecision authorizeCash(String no,String hash,long revision) {
        return port.authorizeRefund(context("AUTHORIZE"),new PointsOperationsPort.RefundAuthorization(no,hash,revision));
    }

    @Override
    public RefundOutcome confirm(RefundConfirmation c) {
        return port.confirmRefund(context("CONFIRM"),c);
    }

    @Override
    public void requestCompensation(RefundAbort c,long revision) {
        port.compensateRefund(context("COMPENSATE"),c,revision);
    }

    @Override
    public void abort(RefundAbort c) {
        port.abortRefund(context("ABORT"),c);
    }

    @Override
    public RefundRecoveryStatus recoveryStatus(String no,String hash) {
        return port.refundStatus(context("STATUS"),no,hash);
    }
}
