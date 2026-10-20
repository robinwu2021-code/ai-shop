package ai.neargo.shop.pay.points.operation;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.points.PointsEventMapper;
import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.redeem.RedeemService;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.spi.points.PointsOperationsPort.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
/** Unified trusted integration; mutations delegate to the existing pay transaction and proof chain. */
@Component @Transactional("payTxManager") public class PointsOperationsPortImpl implements PointsOperationsPort {
    private final ai.neargo.shop.pay.points.store.PointsOperationsStore operations;
    private final boolean enabled;
    private final ai.neargo.shop.pay.points.lifecycle.AccountExpiryGuard expiry;
    private final PointsReadStore read;
    private final PointsInspectionService inspection;
    private final RedeemService redeem;
    private final PointsRefundPort refunds;
    private final PointsFactPort facts;
    private final PointsOperationJournal journal;
    private final PointsEventMapper events;
    private final ObjectMapper json=new ObjectMapper();
    public PointsOperationsPortImpl(@org.springframework.beans.factory.annotation.Value("${shop.points.enabled:false}") boolean enabled, ai.neargo.shop.pay.points.lifecycle.AccountExpiryGuard expiry, ai.neargo.shop.pay.points.store.PointsOperationsStore operations, PointsReadStore read, PointsInspectionService inspection, RedeemService redeem, PointsRefundPort refunds, PointsFactPort facts, PointsOperationJournal journal, PointsEventMapper events) {
        this.enabled=enabled;
        this.expiry=expiry;
        this.operations=operations;
        this.read=read;
        this.inspection=inspection;
        this.redeem=redeem;
        this.refunds=refunds;
        this.facts=facts;
        this.journal=journal;
        this.events=events;
    }
    @Override @Transactional(value="payTxManager", readOnly=true) public Balance balance(String code, Holder holder, String no) {
        var type=type(code);
        require(holder!=null&&nonblank(no), "POINTS_ACCOUNT_INPUT_INVALID");
        var view=inspection.account(code, HolderType.valueOf(holder.name()), no);
        var a=view.snapshot();
        return new Balance(code, type.currency(), holder, no, view.exists(), a.points(Bucket.PENDING), a.points(Bucket.AVAILABLE), a.points(Bucket.HELD), a.frozen(), a.expireAt(), view.version());
    }
    @Override @Transactional(value="payTxManager", readOnly=true) public Receipt status(Operation op, Action action) {
        type(op==null?null:op.typeCode());
        require(action!=null, "POINTS_OPERATION_INPUT_INVALID");
        var saved=journal.find(op, action);
        require(saved!=null, "POINTS_OPERATION_UNKNOWN");
        var receipt=saved.receipt();
        if(action==Action.HOLD||action==Action.RELEASE) {
            var root=read.position(PositionMove.Kind.REDEEM.table(), receipt.reference());
            if(root==null)return receipt;
            String state=(String)root.get("state");
            if("HELD".equals(state)) {
                var snap=json.readValue((String)root.get("payload_json"), ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.HoldSnapshot.class);
                int paid=0, held=0, released=0;
                for(var share:snap.outcome().shares()) {
                    var child=read.position(PositionMove.Kind.REDEEM.table(), PointsKeys.position("R", op.typeCode(), share.subOrderNo()));
                    if(child!=null) {
                        String cs=(String)child.get("state");
                        if("HELD".equals(cs))held++;
                        else if("RELEASED".equals(cs))released++;
                        else paid++;
                    }
                }
                if(paid>0)state=held==0&&released==0?"CONSUMED":"PARTIALLY_CONSUMED";
                else if(released>0)state=held==0?"RELEASED":"PARTIALLY_RELEASED";
            }
            return new Receipt(receipt.operationKey(), receipt.status(), state, receipt.reference(), receipt.reason());
        }
        if(receipt.status()!=Status.ACCEPTED)return receipt;
        var event=DataScopeContext.executeWithoutScope(()->events.findEvent(receipt.reference()));
        if(event==null)return receipt;
        String state=(String)event.get("state");
        Status status=switch(state) {
            case "DONE"->Status.SUCCEEDED;
            case "PROCESSING"->Status.PROCESSING;
            case "RETRY"->Status.RETRYABLE;
            case "MANUAL"->Status.MANUAL;
            default->Status.ACCEPTED;
        };
        String domain=state, reason=null;
        if(status==Status.RETRYABLE||status==Status.MANUAL) {
            String body=operations.eventFailure(op.typeCode(), ((Number)event.get("id")).longValue());
            if(body!=null)reason=json.readTree(body).path("reason").asText(null);
        }
        if(action==Action.EARN&&status==Status.SUCCEEDED) {
            var row=read.position(PositionMove.Kind.GRANT.table(), PointsKeys.position("G", op.typeCode(), op.businessNo()));
            if(row!=null) {
                domain=(String)row.get("state");
                var grant=json.readTree((String)row.get("payload_json"));
                reason=grant.path("outcome").path("reason").asText(null);
                if("SKIPPED".equals(domain))status=Status.SKIPPED;
                else {
                    long issued=((Number)row.get("original_points")).longValue();
                    domain=grant.path("activatedPoints").asLong()>=issued?"AVAILABLE":"PENDING";
                }
            }
        }
        return new Receipt(receipt.operationKey(), status, domain, receipt.reference(), reason);
    }
    @Override public Receipt submitEarning(Context c, Earning input) {
        validate(c);
        require(input!=null&&input.command()!=null&&input.paidFact()!=null, "POINTS_EARN_INPUT_INVALID");
        var cmd=input.command();
        var paid=input.paidFact();
        require(nonblank(cmd.subOrderNo())&&cmd.subOrderNo().length()<=40, "POINTS_EARN_INPUT_INVALID");
        String key=PointsOperationKeys.key(c.operation());
        require(Objects.equals(cmd.typeCode(), c.operation().typeCode())&&Objects.equals(cmd.subOrderNo(), c.operation().businessNo())&&cmd.operationKey()==null, "POINTS_OPERATION_SUBJECT_MISMATCH");
        require(paid.kind()==PointsFactPort.FactKind.PAID&&Objects.equals(paid.typeCode(), cmd.typeCode())&&Objects.equals(paid.subOrderNo(), cmd.subOrderNo())&&Objects.equals(paid.userNo(), cmd.userNo())&&paid.sourceVersion()==cmd.sourceVersion()&&Objects.equals(paid.payment(), cmd.payment())&&paid.payment()!=null&&PointsLifecycleFacts.digest(paid, json::writeValueAsString).equals(paid.payloadHash()), "POINTS_PAID_PROOF_REQUIRED");
        require(nonblank(paid.payment().fundsPath())&&nonblank(paid.payment().accountVersion())&&(!"AGGREGATED".equals(paid.payment().fundsPath())||nonblank(paid.payment().subMerchantId()))&&nonblank(paid.payment().tradeNo())&&nonblank(paid.payment().outTradeNo())&&paid.payment().orderTotalMinor()>=0, "POINTS_PAID_PROOF_REQUIRED");
        require("ONLINE".equals(cmd.payScene())&&cmd.payChannel()!=null&&!"OFFLINE".equals(cmd.payChannel())&&Objects.equals(cmd.storeNo(), paid.payment().storeNo())&&Objects.equals(cmd.merchantNo(), paid.payment().merchantNo()), "POINTS_EARN_PAYMENT_MISMATCH");
        var lines=new HashMap<String, PointsFactPort.LineSnapshot>();
        for(var line:paid.lines())require(lines.put(line.itemId(), line)==null, "POINTS_EARN_INPUT_INVALID");
        require(!cmd.lines().isEmpty()&&cmd.lines().size()==lines.size(), "POINTS_EARN_INPUT_INVALID");
        long deduction=0;
        var redeemed=read.position(PositionMove.Kind.REDEEM.table(),PointsKeys.position("R",cmd.typeCode(),cmd.subOrderNo()));
        if(redeemed!=null) {
            var held=json.readValue((String)redeemed.get("payload_json"),ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot.class);
            require(held.userNo().equals(cmd.userNo()) && held.subOrderNo().equals(cmd.subOrderNo())
                    && held.target().merchantNo().equals(cmd.merchantNo()) && held.target().storeNo().equals(cmd.storeNo())
                    && !"RELEASED".equals(redeemed.get("state")),"POINTS_EARN_REDEMPTION_MISMATCH");
            require(held.target().items().size()==paid.lines().size(),"POINTS_EARN_REDEMPTION_MISMATCH");
            for(int i=0;i<paid.lines().size();i++) {
                var original=held.target().items().get(i);var actual=paid.lines().get(i);
                require(original.itemId().equals(actual.itemId())&&original.goodsMinor()==actual.goodsMinor(),"POINTS_EARN_REDEMPTION_MISMATCH");
            }
            deduction=held.share().minor();
        }
        require(paid.lines().stream().mapToLong(PointsFactPort.LineSnapshot::goodsMinor).reduce(0,Math::addExact)==paid.finalGoodsMinor(),"POINTS_EARN_SNAPSHOT_MISMATCH");
        var cash=ai.neargo.shop.pay.points.policy.EarningCashBasis.allocate(paid.lines(),deduction);
        require(paid.payment().orderTotalMinor()>=Math.subtractExact(paid.finalGoodsMinor(),deduction),"POINTS_PAID_PROOF_REQUIRED");
        var seen=new HashSet<String>();
        for(var line:cmd.lines()) {
            var actual=lines.get(line.itemId());
            require(seen.add(line.itemId())&&actual!=null&&line.quantity()==actual.quantity()&&line.goodsCashMinor()==cash.get(line.itemId())&&line.refundedBeforeGrantMinor()==actual.refundedGoodsMinor()&&line.rule()!=null&&line.rule().mode()==PointsEarnPort.RuleMode.RATIO&&line.rule().value()>=0&&line.rule().value()<=10000, "POINTS_EARN_SNAPSHOT_MISMATCH");
        }
        return journal.apply(c, Action.EARN, input, Receipt.class, ()-> {
            active(c.operation().typeCode());
            require(enabled&&read.enabled(new PointsReadStore.Availability(cmd.typeCode(), cmd.userNo(), cmd.storeNo(), cmd.merchantNo(), type(cmd.typeCode()).market(), cmd.payScene(), cmd.clientType(), ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow.EARN)), "POINTS_DISABLED");
            var account=read.account(cmd.typeCode(), HolderType.USER, cmd.userNo());
            require(!account.frozen(), "POINTS_FROZEN");
            require(!expiry.overdue(account, System.currentTimeMillis()), "POINTS_EXPIRED");
            require(read.position(PositionMove.Kind.GRANT.table(), PointsKeys.position("G", cmd.typeCode(), cmd.subOrderNo()))==null, "POINTS_LEGACY_OPERATION_CONFLICT");
            var effective=new PointsEarnPort.GrantCommand("V2:EARN:"+key, cmd.subOrderNo(), cmd.userNo(), cmd.typeCode(), cmd.storeNo(), cmd.payChannel(), cmd.payScene(), cmd.sourceVersion(), cmd.lines(), cmd.merchantNo(), cmd.clientType(), cmd.payment(), key);
            facts.accept(paid);
            facts.acceptEarning(effective);
            return accepted(c, "SOURCE:EARN:"+effective.eventNo());
        }, r->r);
    }
    @Override public PointsRedeemPort.RedeemQuote quote(String code, String currency, PointsRedeemPort.RedeemQuery q) {
        var type=type(code);
        require(type.currency().equals(currency), "POINTS_OPERATION_CURRENCY_MISMATCH");
        active(code);
        require(q!=null, "POINTS_REDEEM_INPUT_INVALID");
        return redeem.quote(new PointsRedeemPort.RedeemQuery(q.market(), q.userNo(), q.wantPoints(), q.payMode(), q.clientType(), q.targets(), code));
    }
    @Override public PointsRedeemPort.HoldOutcome hold(Context c, PointsRedeemPort.HoldCommand command) {
        validate(c);
        require(command!=null&&Objects.equals(c.operation().businessNo(), command.orderNo())&&command.typeCode()==null&&command.operationKey()==null, "POINTS_OPERATION_SUBJECT_MISMATCH");
        require(command.orderNo().length()<=40&&command.targets().stream().allMatch(t->nonblank(t.subOrderNo())&&t.subOrderNo().length()<=40), "POINTS_REDEEM_INPUT_INVALID");
        return journal.apply(c, Action.HOLD, command, PointsRedeemPort.HoldOutcome.class, ()-> {
            active(c.operation().typeCode());
            return redeem.hold(new PointsRedeemPort.HoldCommand(command.orderNo(), command.market(), command.userNo(), command.wantPoints(), command.payMode(), command.clientType(), command.targets(), command.acceptedPrice(), c.operation().typeCode(), PointsOperationKeys.key(c.operation())));
        }, r->outcome(c, r));
    }
    @Override public PointsRedeemPort.HoldOutcome release(Context c, PointsRedeemPort.ReleaseCommand command) {
        validate(c);
        require(command!=null&&Objects.equals(c.operation().businessNo(), command.orderNo())&&command.typeCode()==null&&command.operationKey()==null, "POINTS_OPERATION_SUBJECT_MISMATCH");
        require(journal.find(c.operation(), Action.HOLD)!=null, "POINTS_OPERATION_UNKNOWN");
        return journal.apply(c, Action.RELEASE, command, PointsRedeemPort.HoldOutcome.class, ()->redeem.release(new PointsRedeemPort.ReleaseCommand(command.orderNo(), command.eventNo(), command.sourceVersion(), c.operation().typeCode(), PointsOperationKeys.key(c.operation()))), r->outcome(c, r));
    }
    @Override public Receipt controlAccount(Context c, AccountControl control) {
        validate(c);
        require(control!=null&&Objects.equals(c.operation().businessNo(), control.userNo()), "POINTS_OPERATION_SUBJECT_MISMATCH");
        return journal.apply(c, Action.FREEZE, control, Receipt.class, ()-> {
            inspection.freeze(new PointsInspectionService.Freeze(c.requestId(), c.operation().typeCode(), control.userNo(), control.expectedVersion(), control.frozen(), control.remark()), control.actor());
            return succeeded(c, control.frozen()?"FROZEN":"UNFROZEN", control.userNo());
        }, r->r);
    }
    @Override public PointsRefundPort.RefundPreparation prepareRefund(Context c, PointsRefundPort.RefundCommand command) {
        validate(c);
        require(command!=null&&Objects.equals(c.operation().typeCode(), command.typeCode())&&Objects.equals(c.currency(), command.currency())&&Objects.equals(c.operation().businessNo(), command.afterSaleNo()), "POINTS_OPERATION_SUBJECT_MISMATCH");
        return journal.apply(c, Action.REFUND_PREPARE, command, PointsRefundPort.RefundPreparation.class, ()->refunds.prepare(command), r->succeeded(c, r.state(), r.preparationNo()));
    }
    @Override public PointsRefundPort.CashDecision authorizeRefund(Context c, RefundAuthorization a) {
        validate(c);
        require(a!=null, "POINTS_REFUND_INPUT_INVALID");
        ownsRefund(c, a.preparationNo());
        return journal.apply(c, Action.REFUND_AUTHORIZE, a, PointsRefundPort.CashDecision.class, ()->refunds.authorizeCash(a.preparationNo(), a.inputHash(), a.revision()), r->succeeded(c, "AUTHORIZED", r.preparationNo()));
    }
    @Override public PointsRefundPort.RefundOutcome confirmRefund(Context c, PointsRefundPort.RefundConfirmation confirmation) {
        validate(c);
        require(confirmation!=null, "POINTS_REFUND_INPUT_INVALID");
        ownsRefund(c, confirmation.preparationNo());
        return journal.apply(c, Action.REFUND_CONFIRM, confirmation, PointsRefundPort.RefundOutcome.class, ()->refunds.confirm(confirmation), r->succeeded(c, r.state(), r.preparationNo()));
    }
    @Override public Receipt abortRefund(Context c, PointsRefundPort.RefundAbort abort) {
        validate(c);
        require(abort!=null, "POINTS_REFUND_INPUT_INVALID");
        ownsRefund(c, abort.preparationNo());
        return journal.apply(c, Action.REFUND_ABORT, abort, Receipt.class, ()-> {
            refunds.abort(abort);
            return succeeded(c, refunds.recoveryStatus(abort.preparationNo(), abort.inputHash()).state(), abort.preparationNo());
        }, r->r);
    }
    @Override public Receipt compensateRefund(Context c, PointsRefundPort.RefundAbort abort, long revision) {
        validate(c);
        require(abort!=null, "POINTS_REFUND_INPUT_INVALID");
        ownsRefund(c, abort.preparationNo());
        return journal.apply(c, Action.REFUND_COMPENSATE, List.of(abort, revision), Receipt.class, ()-> {
            refunds.requestCompensation(abort, revision);
            return succeeded(c, "COMPENSATION_PREPARED", abort.preparationNo());
        }, r->r);
    }
    @Override @Transactional(value="payTxManager", readOnly=true) public PointsRefundPort.RefundRecoveryStatus refundStatus(Context c, String preparation, String hash) {
        validate(c);
        ownsRefund(c, preparation);
        return refunds.recoveryStatus(preparation, hash);
    }
    @Override public Receipt acceptLifecycle(Context c, PointsFactPort.LifecycleFact fact) {
        validate(c);
        require(fact!=null&&Objects.equals(c.operation().typeCode(), fact.typeCode())&&Objects.equals(c.operation().businessNo(), fact.subOrderNo()), "POINTS_OPERATION_SUBJECT_MISMATCH");
        return journal.apply(c, Action.LIFECYCLE, fact, Receipt.class, ()-> {
            facts.accept(fact);
            return accepted(c, "SOURCE:"+fact.eventNo());
        }, r->r);
    }
    @Override public Receipt acceptRefundFact(Context c, PointsFactPort.RefundConfirmationFact fact) {
        validate(c);
        require(fact!=null&&fact.confirmation()!=null&&Objects.equals(c.operation().typeCode(), fact.typeCode()), "POINTS_OPERATION_SUBJECT_MISMATCH");
        ownsRefund(c, fact.confirmation().preparationNo());
        return journal.apply(c, Action.REFUND_FACT, fact, Receipt.class, ()-> {
            facts.acceptRefundConfirmation(fact);
            return accepted(c, "SOURCE:"+fact.eventNo());
        }, r->r);
    }
    @Override public Receipt acceptClosure(Context c, PointsFactPort.RefundClosureFact fact) {
        validate(c);
        require(fact!=null&&Objects.equals(c.operation().typeCode(), fact.typeCode())&&Objects.equals(c.operation().businessNo(), fact.subOrderNo()), "POINTS_OPERATION_SUBJECT_MISMATCH");
        return journal.apply(c, Action.CLOSURE, fact, Receipt.class, ()-> {
            facts.acceptClosure(fact);
            return accepted(c, "SOURCE:"+fact.eventNo());
        }, r->r);
    }
    @Override public Receipt acceptZeroCash(Context c, PointsRefundPort.ZeroCashCompletionFact fact) {
        validate(c);
        require(fact!=null&&Objects.equals(c.operation().typeCode(), fact.typeCode()), "POINTS_OPERATION_SUBJECT_MISMATCH");
        ownsRefund(c, fact.preparationNo());
        return journal.apply(c, Action.ZERO_CASH, fact, Receipt.class, ()-> {
            facts.acceptZeroCash(fact);
            return accepted(c, "SOURCE:"+fact.eventNo());
        }, r->r);
    }
    private void ownsRefund(Context c, String preparation) {
        var row=read.position(PositionMove.Kind.REFUND.table(), preparation);
        require(row!=null, "POINTS_REFUND_PREPARATION_UNKNOWN");
        var cmd=json.readTree((String)row.get("payload_json")).path("command");
        require(c.operation().typeCode().equals(cmd.path("typeCode").asText())&&c.operation().businessNo().equals(cmd.path("afterSaleNo").asText())&&c.currency().equals(cmd.path("currency").asText()), "POINTS_OPERATION_SUBJECT_MISMATCH");
    }
    private void validate(Context c) {
        PointsOperationKeys.key(c==null?null:c.operation());
        require(c.operation().businessNo().length()<=40, "POINTS_OPERATION_INPUT_INVALID");
        require(c.requestId()!=null&&!c.requestId().isBlank()&&c.requestId().length()<=100, "POINTS_OPERATION_INPUT_INVALID");
        require(type(c.operation().typeCode()).currency().equals(c.currency()), "POINTS_OPERATION_CURRENCY_MISMATCH");
    }
    private PointType type(String code) {
        require(nonblank(code), "POINTS_TYPE_REQUIRED");
        return read.type(code).orElseThrow(()->new IllegalArgumentException("POINTS_TYPE_UNKNOWN"));
    }
    private void active(String code) {
        require(type(code).status()==PointType.Status.ACTIVE, "POINTS_TYPE_NOT_ACTIVE");
    }
    private Receipt accepted(Context c, String ref) {
        return new Receipt(PointsOperationKeys.key(c.operation()), Status.ACCEPTED, "READY", ref, null);
    }
    private Receipt succeeded(Context c, String state, String ref) {
        return new Receipt(PointsOperationKeys.key(c.operation()), Status.SUCCEEDED, state, ref, null);
    }
    private Receipt outcome(Context c, PointsRedeemPort.HoldOutcome result) {
        return new Receipt(PointsOperationKeys.key(c.operation()), "SKIPPED".equals(result.state())||"ABSENT".equals(result.state())?Status.SKIPPED:Status.SUCCEEDED, result.state(), result.holdNo(), result.reason());
    }
    private static boolean nonblank(String text) {
        return text!=null&&!text.isBlank();
    }
    private static void require(boolean ok, String code) {
        if(!ok)throw new IllegalArgumentException(code);
    }
}
