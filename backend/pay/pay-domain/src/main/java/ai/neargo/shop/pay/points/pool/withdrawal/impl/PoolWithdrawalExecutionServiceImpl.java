package ai.neargo.shop.pay.points.pool.withdrawal.impl;

import ai.neargo.shop.pay.points.pool.PoolWithdrawalExecutionService;
import ai.neargo.shop.pay.points.pool.PoolWithdrawalService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Dispatch is committed first, unknown operations only query, and no external call holds a pay transaction. */
@Service
public class PoolWithdrawalExecutionServiceImpl implements PoolWithdrawalExecutionService {
    private final PoolWithdrawalService preparation;
    private final ObjectProvider<PointsFundsGateway> gateways;
    public PoolWithdrawalExecutionServiceImpl(PoolWithdrawalService preparation,
                                              ObjectProvider<PointsFundsGateway> gateways) {
        this.preparation=preparation; this.gateways=gateways;
    }
    @Override public PoolWithdrawalService.Outcome execute(String no) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("POINTS_FUNDS_REQUIRES_NO_TRANSACTION");
        }
        var gateway=gateways.getIfAvailable();
        if (gateway==null) { throw new IllegalStateException("POINTS_FUNDS_GATEWAY_REQUIRED"); }
        var claim=preparation.claimDispatch(no);
        if (claim.completed()!=null) { return claim.completed(); }
        FundsResult result;
        try { result=claim.submit()?gateway.submit(claim.command()):gateway.query(claim.command()); }
        catch (RuntimeException uncertain) {
            result=new FundsResult(no,FundsStatus.UNKNOWN,null,claim.command().amountMinor(),
                    "POINTS_TRANSPORT_UNKNOWN");
        }
        if (result==null || result.status()==null || !no.equals(result.ticketNo())
                || result.amountMinor()!=claim.command().amountMinor() || (result.status()==FundsStatus.SUCCEEDED
                && (result.evidenceNo()==null || result.evidenceNo().isBlank()))) {
            result=new FundsResult(no,FundsStatus.UNKNOWN,null,claim.command().amountMinor(),
                    "POINTS_FUNDS_RECEIPT_MISMATCH");
        }
        return preparation.acceptResult(result);
    }
}
