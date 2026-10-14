package ai.neargo.shop.pay.points.port;

import ai.neargo.shop.pay.channel.PayFundsOperations;
import ai.neargo.shop.pay.channel.PayFundsOperations.Operation;
import ai.neargo.shop.pay.channel.PayFundsOperations.Receipt;
import ai.neargo.shop.pay.channel.PayFundsOperations.Request;
import ai.neargo.shop.pay.channel.PayGatewayRouter;
import ai.neargo.shop.pay.channel.entity.SysPayChannel;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Maps native pay money evidence without writing points balances or turning transport uncertainty into failure. */
@Component
public class PayPointsFundsAdapter implements PointsFundsGateway {
    private static final FundsCapabilities NONE=new FundsCapabilities(false,false,false,false);
    private final PayGatewayRouter router;
    private final PayChannelMasterService master;

    public PayPointsFundsAdapter(PayGatewayRouter router,PayChannelMasterService master) {
        this.router=router; this.master=master;
    }
    @Override public FundsCapabilities capabilities(FundsContext context) {
        if (!validContext(context)) { return NONE; }
        try {
            SysPayChannel channel=master.find(context.channel()).orElse(null);
            if (channel==null || !Boolean.TRUE.equals(channel.getEnabled())) { return NONE; }
            PayFundsOperations gateway=nativeGateway(context);
            if (gateway==null) { return NONE; }
            var caps=gateway.fundsCapabilities(nativeContext(context));
            if (caps==null) { return NONE; }
            return new FundsCapabilities(caps.supports(Operation.SUBSIDY),caps.supports(Operation.TRANSFER),
                    caps.supports(Operation.INTERNAL_REPLENISHMENT),caps.supports(Operation.COLLECTION));
        } catch (RuntimeException e) { return NONE; }
    }
    @Override public FundsResult submit(FundsCommand command) {
        requireOutsideTransaction();
        validate(command);
        Request request=nativeRequest(command);
        PayFundsOperations gateway;
        try {
            SysPayChannel channel=master.find(command.context().channel()).orElse(null);
            if (channel==null || !Boolean.TRUE.equals(channel.getEnabled())) {
                return failed(command,"POINTS_CHANNEL_UNAVAILABLE");
            }
            if (!command.currency().equals(channel.getCurrency())) {
                return failed(command,"POINTS_CURRENCY_UNSUPPORTED");
            }
            gateway=nativeGateway(command.context());
            if (gateway==null) { return failed(command,"POINTS_CAPABILITY_UNSUPPORTED"); }
            var capabilities=gateway.fundsCapabilities(request.context());
            if (capabilities==null || !capabilities.supports(request.operation())) {
                return failed(command,"POINTS_CAPABILITY_UNSUPPORTED");
            }
        } catch (RuntimeException e) {
            // Execution has not started; refusing a new promise is distinct from uncertain money movement.
            return failed(command,"POINTS_CAPABILITY_UNAVAILABLE");
        }
        try { return result(command,gateway.executeFunds(request)); }
        catch (RuntimeException e) { return unknown(command,"POINTS_FUNDS_TRANSPORT_UNKNOWN"); }
    }
    @Override public FundsResult query(FundsCommand command) {
        requireOutsideTransaction();
        validate(command);
        // Disablement affects new promises, not recovery of an already committed original request.
        try {
            PayFundsOperations gateway=nativeGateway(command.context());
            if (gateway==null) { return unknown(command,"POINTS_QUERY_UNAVAILABLE"); }
            return result(command,gateway.queryFunds(nativeRequest(command)));
        } catch (RuntimeException e) { return unknown(command,"POINTS_FUNDS_QUERY_UNKNOWN"); }
    }
    private PayFundsOperations nativeGateway(FundsContext context) {
        if (!router.supports(context.channel())) { return null; }
        var gateway=router.of(context.channel());
        return gateway instanceof PayFundsOperations funds?funds:null;
    }
    private FundsResult result(FundsCommand command,Receipt receipt) {
        if (receipt==null || receipt.status()==null || !command.providerIdem().equals(receipt.providerIdem())
                || !command.currency().equals(receipt.currency()) || command.amountMinor()!=receipt.amountMinor()) {
            return unknown(command,"POINTS_FUNDS_RECEIPT_MISMATCH");
        }
        if (receipt.status()==PayFundsOperations.Status.SUCCEEDED) {
            if (blank(receipt.evidenceNo())) { return unknown(command,"POINTS_FUNDS_EVIDENCE_MISSING"); }
            return new FundsResult(command.ticketNo(),FundsStatus.SUCCEEDED,receipt.evidenceNo(),
                    receipt.amountMinor(),null);
        }
        if (receipt.status()==PayFundsOperations.Status.REJECTED) { return failed(command,receipt.reason()); }
        return unknown(command,receipt.reason());
    }
    private Request nativeRequest(FundsCommand command) {
        return new Request(command.ticketNo(),command.providerIdem(),Operation.valueOf(command.purpose().name()),
                nativeContext(command.context()),command.currency(),command.amountMinor());
    }
    private PayFundsOperations.Context nativeContext(FundsContext c) {
        return new PayFundsOperations.Context(PayFundsOperations.Path.valueOf(c.path().name()),c.channel(),
                c.merchantNo(),c.accountVersion(),c.subMerchantId(),c.tradeNo(),c.outTradeNo(),c.orderTotalMinor());
    }
    private boolean validContext(FundsContext context) {
        return context!=null && context.path()!=null && !blank(context.channel()) && !blank(context.merchantNo())
                && !blank(context.accountVersion()) && context.orderTotalMinor()>=0;
    }
    private void validate(FundsCommand c) {
        if (c==null || blank(c.ticketNo()) || blank(c.providerIdem()) || c.purpose()==null || blank(c.currency())
                || c.amountMinor()<=0 || !validContext(c.context())) {
            throw new IllegalArgumentException("POINTS_FUNDS_COMMAND_INVALID");
        }
    }
    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("POINTS_FUNDS_REQUIRES_NO_TRANSACTION");
        }
    }
    private boolean blank(String value) { return value==null || value.isBlank(); }
    private FundsResult failed(FundsCommand c,String reason) {
        return new FundsResult(c.ticketNo(),FundsStatus.FAILED,null,c.amountMinor(),reason);
    }
    private FundsResult unknown(FundsCommand c,String reason) {
        return new FundsResult(c.ticketNo(),FundsStatus.UNKNOWN,null,c.amountMinor(),reason);
    }
}
