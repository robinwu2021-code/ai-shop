package ai.neargo.shop.pay.points.refund.impl;

import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.refund.PointsReceivableRecoveryExecutionService;
import ai.neargo.shop.pay.points.refund.PointsReceivableRecoveryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** An unknown store collection queries its original intent; real incoming cash never repays customer rights twice. */
@Service
public class PointsReceivableRecoveryExecutionServiceImpl implements PointsReceivableRecoveryExecutionService {
    private final PointsReceivableRecoveryService preparation;
    private final ObjectProvider<PointsFundsGateway> gateways;
    public PointsReceivableRecoveryExecutionServiceImpl(PointsReceivableRecoveryService preparation,
                                                       ObjectProvider<PointsFundsGateway> gateways) {
        this.preparation=preparation; this.gateways=gateways;
    }
    @Override public PointsReceivableRecoveryService.Outcome execute(String no) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("POINTS_FUNDS_REQUIRES_NO_TRANSACTION");
        }
        var gateway=gateways.getIfAvailable();
        if (gateway==null) { throw new IllegalStateException("POINTS_FUNDS_GATEWAY_REQUIRED"); }
        var claim=preparation.claim(no);
        if (claim.completed()!=null) { return claim.completed(); }
        var command=claim.command();
        FundsResult result;
        try { result=claim.submit()?gateway.submit(command):gateway.query(command); }
        catch (RuntimeException uncertain) {
            result=new FundsResult(no,FundsStatus.UNKNOWN,null,command.amountMinor(),"POINTS_TRANSPORT_UNKNOWN");
        }
        if (result==null || result.status()==null || !no.equals(result.ticketNo())
                || result.amountMinor()!=command.amountMinor() || (result.status()==FundsStatus.SUCCEEDED
                && (result.evidenceNo()==null || result.evidenceNo().isBlank()))) {
            result=new FundsResult(no,FundsStatus.UNKNOWN,null,command.amountMinor(),"POINTS_FUNDS_RECEIPT_MISMATCH");
        }
        return preparation.accept(result);
    }
}
