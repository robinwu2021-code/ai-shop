package ai.neargo.shop.pay.points.payout.impl;

import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutOutcome;
import ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService;
import ai.neargo.shop.pay.points.payout.PointsPayoutService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Each proxied pay transaction finishes before the native funds gateway is called. */
@Service
public class PointsPayoutExecutionServiceImpl implements PointsPayoutExecutionService {
    private final PointsPayoutService preparation;
    private final ObjectProvider<PointsFundsGateway> gateways;
    public PointsPayoutExecutionServiceImpl(PointsPayoutService preparation,
                                            ObjectProvider<PointsFundsGateway> gateways) {
        this.preparation=preparation; this.gateways=gateways;
    }
    @Override public PayoutOutcome execute(String no) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("POINTS_FUNDS_REQUIRES_NO_TRANSACTION");
        }
        PointsFundsGateway gateway=gateways.getIfAvailable();
        if (gateway==null) { throw new IllegalStateException("POINTS_FUNDS_GATEWAY_REQUIRED"); }
        var claim=preparation.claimDispatch(no);
        if (claim.completed()!=null) { return claim.completed(); }
        FundsResult result;
        try { result=claim.submit()?gateway.submit(claim.command()):gateway.query(claim.command()); }
        catch (RuntimeException e) {
            result=new FundsResult(no,FundsStatus.UNKNOWN,null,claim.command().amountMinor(),
                    "POINTS_TRANSPORT_UNKNOWN");
        }
        if (result==null || result.status()==null || !claim.command().ticketNo().equals(result.ticketNo())
                || claim.command().amountMinor()!=result.amountMinor()
                || (result.status()==FundsStatus.SUCCEEDED
                && (result.evidenceNo()==null || result.evidenceNo().isBlank()))) {
            result=new FundsResult(no,FundsStatus.UNKNOWN,null,claim.command().amountMinor(),
                    "POINTS_FUNDS_RECEIPT_MISMATCH");
        }
        return preparation.acceptResult(result);
    }
}
