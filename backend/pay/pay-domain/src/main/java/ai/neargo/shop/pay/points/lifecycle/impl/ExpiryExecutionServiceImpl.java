package ai.neargo.shop.pay.points.lifecycle.impl;

import ai.neargo.shop.pay.points.lifecycle.ExpiryExecutionService;
import ai.neargo.shop.pay.points.lifecycle.ExpiryService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The funds intent is committed before any external operation. */
@Service
public class ExpiryExecutionServiceImpl implements ExpiryExecutionService {
    private final ExpiryService preparation;
    private final ObjectProvider<PointsFundsGateway> gateways;
    public ExpiryExecutionServiceImpl(ExpiryService preparation,ObjectProvider<PointsFundsGateway> gateways) {
        this.preparation=preparation; this.gateways=gateways;
    }
    @Override public FundsResult execute(String no) {
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
        return preparation.accept(no,result);
    }
}
