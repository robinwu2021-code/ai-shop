package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.*;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.service.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/** No ambient financial transaction: pay hold -> original core create/decision -> original pay completion. */
@Service
public class LegacyCouponOrderCreationCoordinatorImpl implements LegacyCouponOrderCreationCoordinator {
    private final LegacyCouponRouteResolver routes;
    private final LegacyCouponCheckoutJournal journal;
    private final LegacyCouponOrderPricingService pricing;
    private final LegacyCouponBoundOrderService core;
    private final LegacyCouponUsePort pay;
    private final ObjectMapper json;
    public LegacyCouponOrderCreationCoordinatorImpl(LegacyCouponRouteResolver routes,LegacyCouponCheckoutJournal journal,
            LegacyCouponOrderPricingService pricing,LegacyCouponBoundOrderService core,LegacyCouponUsePort pay,ObjectMapper json) {
        this.routes=routes;this.journal=journal;this.pricing=pricing;this.core=core;this.pay=pay;this.json=json;
    }
    @Override public OrderVO createFor(String user,OrderService.CreateOrderCommand command,String key,Integer payMinutes) {
        String actor=SecurityUtils.currentUserNo();
        String request=hash(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(command));
        String identity=hash("MAIN|"+user+"|"+key+"|"+request),checkout="LC"+identity.substring(0,62);
        var ctx=new EntitlementContext("MAIN",actor,checkout,request);
        CheckoutFact fact=find(ctx,checkout);
        if(fact==null) {
            LegacyCouponRouteResolver.Route route;
            try {route=routes.resolve(ctx,user,command.couponNo());}
            catch(BizException failure) {
                if(failure.errorCode()==ErrorCode.NOT_FOUND) throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
                throw failure;
            }
            if(route.ownerSystem()==OwnerSystem.CORE_LEGACY) return core.createCoreFor(user,command,key,payMinutes);
            outside();
            if(key==null || key.isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST);
            var actual=pricing.snapshot(user,command);
            fact=journal.stage(ctx,new LegacyCouponCheckoutJournal.Stage(IntentKind.CHECKOUT,checkout,
                    "LO"+identity.substring(0,62),user,route.entityNo(),route.source(),route.targetNo(),route.migrationBatchNo(),
                    route.cutoverProofHash(),actual.currency(),actual.groups(),actual.commandHash()));
        }
        outside();
        if(!user.equals(fact.userNo()) || !actor.equals(fact.selectionActorNo())
                || !command.couponNo().equals(fact.origin().sourceNo())) throw BizException.of(ErrorCode.CONFLICT);
        var decision=journal.decision(ctx,checkout);
        if(decision.isPresent()) {
            recover(ctx,checkout);
            if(decision.get().outcome()==Outcome.ABORT) throw BizException.of(ErrorCode.CONFLICT);
            return core.readCreated(ctx,checkout,user);
        }
        var held=pay.prepare(ctx,new LegacyCouponUsePort.Prepare(checkout,fact.operationNo(),user,fact.entityNo()));
        if(held.status()!=LegacyCouponUsePort.State.HELD) {
            recover(ctx,checkout);
            return core.readCreated(ctx,checkout,user);
        }
        OrderVO order;
        try {order=core.createBound(user,command,key,payMinutes,journal.bindingFor(ctx,checkout,held));}
        catch(RuntimeException failure) {
            // The same journal row excludes an in-flight/late core create. Never infer an abort from a pay timeout.
            try {
                if(journal.decision(ctx,checkout).isEmpty()) journal.abort(ctx,checkout);
                if(journal.decision(ctx,checkout).orElseThrow().outcome()==Outcome.ABORT) recover(ctx,checkout);
            } catch(RuntimeException recoveryFailure) {failure.addSuppressed(recoveryFailure);}
            throw failure;
        }
        recover(ctx,checkout);
        return order;
    }
    @Override public LegacyCouponUsePort.Operation recover(EntitlementContext ctx,String checkout) {
        outside();var fact=journal.resolve(ctx,checkout);
        var decision=journal.decision(ctx,checkout).orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        var command=new LegacyCouponUsePort.Complete(checkout,fact.operationNo(),fact.entityNo(),decision.decisionNo());
        var reversal=journal.reversal(ctx,checkout);
        if(reversal.isPresent() && decision.outcome()!=Outcome.COMMIT) throw BizException.of(ErrorCode.CONFLICT);
        var result=reversal.isPresent()?pay.reverse(ctx,new LegacyCouponUsePort.Complete(checkout,fact.operationNo(),
                fact.entityNo(),reversal.get().decisionNo()))
                :decision.outcome()==Outcome.COMMIT?pay.commit(ctx,command):pay.release(ctx,command);
        if(decision.outcome()==Outcome.COMMIT && result.status()!=LegacyCouponUsePort.State.COMMITTED
                && result.status()!=LegacyCouponUsePort.State.REVERSED
                || decision.outcome()==Outcome.ABORT && result.status()!=LegacyCouponUsePort.State.RELEASED)
            throw BizException.of(ErrorCode.CONFLICT);
        journal.acknowledge(ctx,checkout,result);
        return result;
    }
    private CheckoutFact find(EntitlementContext ctx,String no) {
        try {return journal.resolve(ctx,no);}
        catch(BizException e) {if(e.errorCode()==ErrorCode.NOT_FOUND) return null;throw e;}
    }
    private static void outside() {if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);}
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException("SHA-256 unavailable");}
    }
}
