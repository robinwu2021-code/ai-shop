package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.trade.service.LegacyCouponBoundOrderService;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.CheckoutFact;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.Decision;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.Reversal;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.IntentKind;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.MerchantGroup;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.Outcome;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.ReverseReason;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.trade.entitlement.entity.OrdLegacyCouponCheckout;
import ai.neargo.shop.trade.mapper.LegacyCouponCheckoutMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.service.LegacyCouponCheckoutJournal;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Only core can establish checkout truth. No pay/network calls occur while this journal row is held. */
@Service
public class LegacyCouponCheckoutJournalImpl implements LegacyCouponCheckoutJournal {
    private final LegacyCouponCheckoutMapper rows;
    private final LegacyCouponIdentityPort identities;
    private final OrderMapper orders;
    private final SubOrderMapper subs;
    private final AfterSaleMapper afterSales;
    private final ObjectProvider<PlatformSwitchPort> switches;
    private final ObjectMapper json;
    private final TransactionTemplate fresh;
    private final boolean enabled;
    private final Clock clock;
    private final OutboxEventBus events;
    public LegacyCouponCheckoutJournalImpl(LegacyCouponCheckoutMapper rows,LegacyCouponIdentityPort identities,
            OrderMapper orders,SubOrderMapper subs,AfterSaleMapper afterSales,ObjectProvider<PlatformSwitchPort> switches,ObjectMapper json,PlatformTransactionManager transactions,OutboxEventBus events,
            @Qualifier("legacyCouponCheckoutClock") ObjectProvider<Clock> clocks,
            @Value("${shop.core.entitlement.legacy-coupon.enabled:false}") boolean enabled) {
        this.rows=rows;this.identities=identities;this.orders=orders;this.subs=subs;this.afterSales=afterSales;this.switches=switches;this.json=json;this.enabled=enabled;this.events=events;this.clock=clocks.getIfAvailable(Clock::systemUTC);
        fresh=new TransactionTemplate(transactions);
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fresh.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public LegacyCouponBoundOrderService.Binding bindingFor(EntitlementContext ctx,String no,LegacyCouponUsePort.Operation held) {
        check(ctx,no);return fresh.execute(s->scoped(()->{
            var row=load(ctx,no,false);same(actor(row),ctx.actorNo());verifyHeld(fact(row),held);
            return new LegacyCouponBoundOrderService.Binding(ctx,no,row.getCommandHash(),held);
        }));
    }
    @Override public CheckoutFact stage(EntitlementContext ctx,Stage cmd) {
        noAmbient(); check(ctx,cmd.checkoutNo());
        String stageHash=hash(write(cmd));
        var original=fresh.execute(s->scoped(()->rows.find(ctx.tenantNo(),cmd.checkoutNo())));
        if(original!=null) {same(original.getStageHash(),stageHash);same(actor(original),ctx.actorNo());return fact(original);}
        if(!enabled || !"MAIN".equals(ctx.tenantNo()) || !"CNY".equals(cmd.currency())
                || cmd.origin().sourceSystem()==SourceSystem.PAY)
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var origins=identities.ownedOrigins(ctx,cmd.userNo(),cmd.origin().sourceNo());
        if(origins==null || origins.isEmpty() || origins.stream().noneMatch(o->o.source().equals(cmd.origin()))) conflict();
        for(var origin:origins) {
            if(origin.deleted() || !origin.rootPresent() || !origin.entityConsistent()
                    || !"COMMIT_DECIDED".equals(origin.rootState()) || !"MERCHANT".equals(origin.funder())
                    || !Objects.equals(cmd.entityNo(),origin.entityNo())
                    || !Objects.equals(cmd.ownerBatchNo(),origin.migrationBatchNo())
                    || !Objects.equals(cmd.ownerPlanHash(),origin.cutoverProofHash())) conflict();
        }
        Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String economics=hash(ctx.tenantNo()+"|"+stageHash);
        var fact=new CheckoutFact(1,cmd.intentKind(),ctx.tenantNo(),cmd.checkoutNo(),cmd.operationNo(),cmd.userNo(),cmd.entityNo(),
                cmd.origin(),cmd.targetInstanceNo(),cmd.ownerBatchNo(),cmd.ownerPlanHash(),cmd.currency(),cmd.groups(),economics,
                ctx.actorNo(),now,now.plusSeconds(cmd.intentKind()==IntentKind.QUOTE_ONLY?120:900));
        return fresh.execute(s->scoped(()->{
            var row=new OrdLegacyCouponCheckout();row.setTenantNo(ctx.tenantNo());row.setCheckoutNo(cmd.checkoutNo());
            row.setOperationNo(cmd.operationNo());row.setUserNo(cmd.userNo());row.setEntityNo(cmd.entityNo());
            row.setCommandHash(cmd.commandHash());row.setStageHash(stageHash);row.setEconomicHash(economics);
            row.setFactSnapshot(write(fact));row.setStatus("OPEN");
            row.setPayState(cmd.intentKind()==IntentKind.CHECKOUT?"PENDING":"NONE");
            row.setRecoveryDueAt(cmd.intentKind()==IntentKind.CHECKOUT?LocalDateTime.ofInstant(fact.deadlineAt(),ZoneOffset.UTC):null);row.setVersion(0L);row.setDeleted(0);
            row.setCreatedAt(LocalDateTime.ofInstant(now,ZoneOffset.UTC));row.setUpdatedAt(row.getCreatedAt());
            row.setCreatedBy(ctx.actorNo());row.setUpdatedBy(ctx.actorNo());rows.claim(row);
            var saved=load(ctx,cmd.checkoutNo(),true);same(saved.getStageHash(),stageHash);same(actor(saved),ctx.actorNo());
            return fact(saved);
        }));
    }
    @Override public CheckoutFact resolve(EntitlementContext ctx,String no) {
        check(ctx,no);return fresh.execute(s->scoped(()->fact(load(ctx,no,false))));
    }
    @Override public Optional<Decision> decision(EntitlementContext ctx,String no) {
        check(ctx,no);return fresh.execute(s->scoped(()->decision(load(ctx,no,false))));
    }
    @Override public Optional<Reversal> reversal(EntitlementContext ctx,String no) {
        check(ctx,no);return fresh.execute(s->scoped(()->{
            var row=load(ctx,no,false);
            return row.getReversalSnapshot()==null?Optional.empty():Optional.of(read(row.getReversalSnapshot(),Reversal.class));
        }));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public CheckoutFact bind(EntitlementContext ctx,String no,String user,String commandHash,List<MerchantGroup> actual,
                     LegacyCouponUsePort.Operation held) {
        check(ctx,no);return scoped(()->{
            var row=load(ctx,no,true);var fact=fact(row);same(actor(row),ctx.actorNo());same(row.getUserNo(),user);
            same(row.getCommandHash(),commandHash);same(fact.groups(),actual);
            if(!"OPEN".equals(row.getStatus()) || fact.intentKind()!=IntentKind.CHECKOUT || !clock.instant().isBefore(fact.deadlineAt())) conflict();
            verifyHeld(fact,held);
            if(row.getHeldSnapshot()!=null) same(json.readTree(row.getHeldSnapshot()),json.readTree(write(held)));
            else {row.setHeldSnapshot(write(held));save(row,ctx);}
            return fact;
        });
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Decision commit(EntitlementContext ctx,String no,String orderNo,LegacyCouponUsePort.Operation held) {
        check(ctx,no);ContractChecks.id(orderNo);return scoped(()->{
            var row=load(ctx,no,true);var fact=fact(row);same(actor(row),ctx.actorNo());verifyHeld(fact,held);
            if(row.getHeldSnapshot()==null) conflict();
            same(json.readTree(row.getHeldSnapshot()),json.readTree(write(held)));
            var existing=decision(row);
            if(existing.isPresent()) {
                var d=existing.get();if(d.outcome()!=Outcome.COMMIT) conflict();same(d.orderNo(),orderNo);
                same(d.discountMinor(),held.discountMinor());return d;
            }
            if(!"OPEN".equals(row.getStatus()) || fact.intentKind()!=IntentKind.CHECKOUT) conflict();
            var order=orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo,ctx.tenantNo())
                    .eq(OrdOrder::getOrderNo,orderNo).last("FOR UPDATE"));
            if(order==null || !"WAIT_PAY".equals(order.getStatus()) || !fact.userNo().equals(order.getUserNo())
                    || !fact.currency().equals(order.getCurrency()) || order.getDiscountAmount()==null
                    || order.getDiscountAmount()<held.discountMinor()) conflict();
            var d=new Decision(no,fact.operationNo(),BizKey.next(BizKey.ENTITLEMENT_DECISION),Outcome.COMMIT,orderNo,
                    fact.economicHash(),held.discountMinor(),clock.instant().truncatedTo(ChronoUnit.MILLIS));
            row.setOrderNo(orderNo);row.setStatus("COMMITTED");row.setDecisionSnapshot(write(d));pending(row);save(row,ctx);
            events.publish(new LegacyCouponCheckoutEvents.Decided(ctx.tenantNo(),fact.entityNo(),no,fact.operationNo()));return d;
        });
    }
    @Override @Transactional
    public boolean returnCancelledOrder(EntitlementContext ctx,String orderNo) {
        check(ctx,orderNo);return scoped(()->{
            var row=rows.lockByOrder(ctx.tenantNo(),orderNo);
            if(row==null) return false;
            if(row.getReversalSnapshot()!=null) return true;
            var fact=fact(row);var original=decision(row).orElseThrow(()->BizException.of(ErrorCode.CONFLICT));
            if(original.outcome()!=Outcome.COMMIT) conflict();
            var order=orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo,ctx.tenantNo())
                    .eq(OrdOrder::getOrderNo,orderNo));
            if(order==null || !fact.userNo().equals(order.getUserNo()) || !fact.currency().equals(order.getCurrency())) conflict();
            if(!Set.of(OrdOrder.CANCELLED,OrdOrder.CLOSED).contains(order.getStatus())) return true;
            var children=subs.selectList(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo,ctx.tenantNo())
                    .eq(OrdSubOrder::getOrderNo,orderNo));
            if(children.isEmpty() || children.stream().anyMatch(s->!OrdSubOrder.CANCELLED.equals(s.getStatus()))) conflict();
            var reversal=new Reversal(fact.checkoutNo(),fact.operationNo(),BizKey.next(BizKey.ENTITLEMENT_DECISION),
                    orderNo,orderNo,fact.economicHash(),fact.currency(),original.discountMinor(),ReverseReason.CANCELLED,
                    clock.instant().truncatedTo(ChronoUnit.MILLIS));
            row.setReversalSnapshot(write(reversal));pending(row);save(row,ctx);
            events.publish(new LegacyCouponCheckoutEvents.Decided(ctx.tenantNo(),fact.entityNo(),fact.checkoutNo(),fact.operationNo()));
            return true;
        });
    }
    @Override public Optional<Reversal> returnRefundedOrder(EntitlementContext ctx,String afterSaleNo) {
        noAmbient();check(ctx,afterSaleNo);
        var policy=switches.getIfAvailable();boolean allowed=policy==null || policy.bool("refund.return-coupon",true);
        return fresh.execute(tx->scoped(()->{
            var afterSale=afterSales.selectOne(Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getTenantNo,ctx.tenantNo())
                    .eq(OrdAfterSale::getAfterSaleNo,afterSaleNo));
            if(afterSale==null) throw BizException.of(ErrorCode.NOT_FOUND);
            if(!OrdAfterSale.REFUNDED.equals(afterSale.getStatus()) || afterSale.getRefundedAt()==null) conflict();
            var child=subs.selectOne(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo,ctx.tenantNo())
                    .eq(OrdSubOrder::getSubOrderNo,afterSale.getSubOrderNo()));
            if(child==null || !Objects.equals(child.getOrderNo(),afterSale.getOrderNo())
                    || !Objects.equals(child.getUserNo(),afterSale.getUserNo()) || !Objects.equals(child.getEntityNo(),afterSale.getEntityNo())) conflict();
            var row=rows.lockByOrder(ctx.tenantNo(),child.getOrderNo());
            if(row==null) return Optional.empty();
            if(row.getReversalSnapshot()!=null) return Optional.of(read(row.getReversalSnapshot(),Reversal.class));
            if(!allowed) return Optional.empty();
            var fact=fact(row);var original=decision(row).orElseThrow(()->BizException.of(ErrorCode.CONFLICT));
            if(original.outcome()!=Outcome.COMMIT || !fact.userNo().equals(child.getUserNo())) conflict();
            var order=orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo,ctx.tenantNo())
                    .eq(OrdOrder::getOrderNo,child.getOrderNo()));
            if(order==null || !fact.userNo().equals(order.getUserNo()) || !fact.currency().equals(order.getCurrency())) conflict();
            if(!OrdOrder.PAID.equals(order.getStatus())) return Optional.empty();
            var children=subs.selectList(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo,ctx.tenantNo())
                    .eq(OrdSubOrder::getOrderNo,child.getOrderNo()));
            if(children.isEmpty() || children.stream().anyMatch(c->!fullyReturned(ctx,c))) return Optional.empty();
            var reversal=new Reversal(fact.checkoutNo(),fact.operationNo(),BizKey.next(BizKey.ENTITLEMENT_DECISION),
                    order.getOrderNo(),afterSaleNo,fact.economicHash(),fact.currency(),original.discountMinor(),ReverseReason.FULL_REFUND,
                    clock.instant().truncatedTo(ChronoUnit.MILLIS));
            row.setReversalSnapshot(write(reversal));pending(row);save(row,ctx);
            events.publish(new LegacyCouponCheckoutEvents.Decided(ctx.tenantNo(),fact.entityNo(),fact.checkoutNo(),fact.operationNo()));
            return Optional.of(reversal);
        }));
    }
    private boolean fullyReturned(EntitlementContext ctx,OrdSubOrder child) {
        if(OrdSubOrder.CANCELLED.equals(child.getStatus())) return true;
        if(!OrdSubOrder.REFUNDED.equals(child.getStatus())) return false;
        var settled=afterSales.selectList(Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getTenantNo,ctx.tenantNo())
                .eq(OrdAfterSale::getOrderNo,child.getOrderNo()).eq(OrdAfterSale::getSubOrderNo,child.getSubOrderNo())
                .eq(OrdAfterSale::getUserNo,child.getUserNo()).eq(OrdAfterSale::getEntityNo,child.getEntityNo())
                .eq(OrdAfterSale::getStatus,OrdAfterSale.REFUNDED).isNotNull(OrdAfterSale::getRefundedAt));
        if(settled.isEmpty()) return false;
        boolean cash=child.getPayAmount()!=null && child.getRefundedMinor()!=null
                && child.getPayAmount().equals(child.getRefundedMinor())
                && ("FULL".equals(child.getRefundProgress()) || child.getPayAmount()==0L);
        // Preserve the core's existing whole-order POINTS-compensation closure; it is never labelled as cash returned.
        boolean compensated=settled.stream().anyMatch(a->OrdAfterSale.REFUND_MODE_POINTS.equals(a.getRefundMode())
                && a.getRefundPoints()!=null && a.getRefundPoints()>=0);
        return cash || compensated;
    }
    @Override public Decision abort(EntitlementContext ctx,String no) {
        noAmbient();check(ctx,no);return fresh.execute(s->scoped(()->{
            var row=load(ctx,no,true);var fact=fact(row);same(actor(row),ctx.actorNo());
            var existing=decision(row);
            if(existing.isPresent()) {if(existing.get().outcome()!=Outcome.ABORT) conflict();return existing.get();}
            if(!"OPEN".equals(row.getStatus()) || fact.intentKind()!=IntentKind.CHECKOUT) conflict();
            var d=new Decision(no,fact.operationNo(),BizKey.next(BizKey.ENTITLEMENT_DECISION),Outcome.ABORT,null,
                    fact.economicHash(),null,clock.instant().truncatedTo(ChronoUnit.MILLIS));
            row.setStatus("ABORTED");row.setDecisionSnapshot(write(d));pending(row);save(row,ctx);
            events.publish(new LegacyCouponCheckoutEvents.Decided(ctx.tenantNo(),fact.entityNo(),no,fact.operationNo()));return d;
        }));
    }
    @Override public List<OrderVO.DiscountLine> discountLines(String tenant,String subNo) {
        ContractChecks.id(tenant);ContractChecks.id(subNo);return scoped(()->{
            var sub=subs.selectOne(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo,tenant).eq(OrdSubOrder::getSubOrderNo,subNo));
            if(sub==null) return List.of();
            var row=rows.findByOrder(tenant,sub.getOrderNo());
            if(row==null || !"COMMITTED".equals(row.getStatus()) || row.getReversalSnapshot()!=null || row.getHeldSnapshot()==null) return List.of();
            var f=fact(row);var held=read(row.getHeldSnapshot(),LegacyCouponUsePort.Operation.class);
            String title=rows.originalTitle(tenant,f.origin().sourceSystem().name(),f.userNo(),f.origin().sourceNo());
            return held.allocations().stream().filter(a->Objects.equals(a.merchantNo(),sub.getEntityNo())
                    && Objects.equals(a.storeNo(),sub.getStoreNo()))
                    .map(a->new OrderVO.DiscountLine("COUPON",title,a.discountMinor(),"MERCHANT")).toList();
        });
    }
    @Override public String originalTitle(String tenant,String no,boolean returned) {
        ContractChecks.id(tenant);ContractChecks.id(no);return scoped(()->{
            var row=rows.findByOrder(tenant,no);
            if(row==null || !"COMMITTED".equals(row.getStatus())) return null;
            if(returned? !"REVERSED".equals(row.getPayState()):row.getReversalSnapshot()!=null) return null;
            var f=fact(row);return rows.originalTitle(tenant,f.origin().sourceSystem().name(),f.userNo(),f.origin().sourceNo());
        });
    }
    @Override public boolean decideForRecovery(EntitlementContext ctx,String no) {
        noAmbient();check(ctx,no);return fresh.execute(tx->scoped(()->{
            var row=load(ctx,no,true);var fact=fact(row);
            if(!"PENDING".equals(row.getPayState())) return false;
            if(fact.intentKind()==IntentKind.QUOTE_ONLY) {
                row.setPayState("NONE");row.setRecoveryDueAt(null);save(row,ctx);return false;
            }
            if(decision(row).isPresent()) return true;
            if(!"OPEN".equals(row.getStatus())) conflict();
            if(clock.instant().isBefore(fact.deadlineAt())) {
                row.setRecoveryDueAt(LocalDateTime.ofInstant(fact.deadlineAt(),ZoneOffset.UTC));save(row,ctx);return false;
            }
            // This is the actual source owner's expired selection, protected against any in-flight/late create.
            var d=new Decision(no,fact.operationNo(),BizKey.next(BizKey.ENTITLEMENT_DECISION),Outcome.ABORT,null,
                    fact.economicHash(),null,clock.instant().truncatedTo(ChronoUnit.MILLIS));
            row.setStatus("ABORTED");row.setDecisionSnapshot(write(d));pending(row);save(row,ctx);
            events.publish(new LegacyCouponCheckoutEvents.Decided(ctx.tenantNo(),fact.entityNo(),no,fact.operationNo()));return true;
        }));
    }
    @Override public void acknowledge(EntitlementContext ctx,String no,LegacyCouponUsePort.Operation completed) {
        noAmbient();check(ctx,no);ContractChecks.present(completed);fresh.executeWithoutResult(tx->scoped(()->{
            var row=load(ctx,no,true);var fact=fact(row);var d=decision(row).orElseThrow(()->BizException.of(ErrorCode.CONFLICT));
            same(fact.checkoutNo(),completed.checkoutNo());same(fact.operationNo(),completed.operationNo());
            same(fact.targetInstanceNo(),completed.instanceNo());same(fact.entityNo(),completed.entityNo());
            same(fact.currency(),completed.currency());same(fact.economicHash(),completed.economicHash());
            var reversal=row.getReversalSnapshot()==null?null:read(row.getReversalSnapshot(),Reversal.class);
            String expected=reversal!=null?"REVERSED":d.outcome()==Outcome.COMMIT?"COMMITTED":"RELEASED";
            same(expected,completed.status().name());
            if(d.outcome()==Outcome.COMMIT) same(d.discountMinor(),completed.discountMinor());
            String decisionNo=reversal==null?d.decisionNo():reversal.decisionNo();
            if(expected.equals(row.getPayState()) && decisionNo.equals(row.getPayDecisionNo())) return null;
            row.setPayState(expected);row.setPayDecisionNo(decisionNo);row.setPayAckedAt(utcNow());
            row.setRecoveryDueAt(null);save(row,ctx);return null;
        }));
    }
    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);
    }
    private void pending(OrdLegacyCouponCheckout row) {
        row.setPayState("PENDING");row.setPayDecisionNo(null);row.setPayAckedAt(null);
        row.setRecoveryDueAt(utcNow());
    }
    private void verifyHeld(CheckoutFact f,LegacyCouponUsePort.Operation held) {
        if(held==null || held.status()!=LegacyCouponUsePort.State.HELD) conflict();
        same(f.checkoutNo(),held.checkoutNo());same(f.operationNo(),held.operationNo());same(f.targetInstanceNo(),held.instanceNo());
        same(f.entityNo(),held.entityNo());same(f.currency(),held.currency());same(f.economicHash(),held.economicHash());
        for(var allocation:held.allocations()) {
            var group=f.groups().stream().filter(g->g.groupKey().equals(allocation.groupKey())).findFirst().orElseThrow(()->BizException.of(ErrorCode.CONFLICT));
            same(group.merchantNo(),allocation.merchantNo());same(group.storeNo(),allocation.storeNo());
            same(f.entityNo(),group.merchantNo());if(allocation.discountMinor()>group.goodsAmountMinor()) conflict();
        }
    }
    private Optional<Decision> decision(OrdLegacyCouponCheckout row) {
        return row.getDecisionSnapshot()==null?Optional.empty():Optional.of(read(row.getDecisionSnapshot(),Decision.class));
    }
    private OrdLegacyCouponCheckout load(EntitlementContext ctx,String no,boolean lock) {
        var row=lock?rows.lock(ctx.tenantNo(),no):rows.find(ctx.tenantNo(),no);
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);return row;
    }
    private CheckoutFact fact(OrdLegacyCouponCheckout row) {
        var fact=read(row.getFactSnapshot(),CheckoutFact.class);
        same(row.getTenantNo(),fact.tenantNo());same(row.getCheckoutNo(),fact.checkoutNo());same(row.getOperationNo(),fact.operationNo());
        same(row.getUserNo(),fact.userNo());same(row.getEntityNo(),fact.entityNo());same(row.getEconomicHash(),fact.economicHash());return fact;
    }
    private String actor(OrdLegacyCouponCheckout row) {return fact(row).selectionActorNo();}
    private void save(OrdLegacyCouponCheckout row,EntitlementContext ctx) {
        row.setUpdatedAt(utcNow());row.setUpdatedBy(ctx.actorNo());
        var change=new LegacyCouponCheckoutMapper.StateChange(row.getTenantNo(),row.getCheckoutNo(),row.getVersion(),
                row.getStatus(),row.getHeldSnapshot(),row.getDecisionSnapshot(),row.getReversalSnapshot(),row.getOrderNo(),
                row.getPayState(),row.getPayDecisionNo(),row.getPayAckedAt(),row.getRecoveryDueAt(),row.getUpdatedAt(),row.getUpdatedBy());
        if(rows.updateState(change)!=1) conflict();
        row.setVersion(row.getVersion()+1);
    }
    private static void check(EntitlementContext ctx,String no) {ContractChecks.present(ctx);ContractChecks.id(no);}
    private static void noAmbient() {if(TransactionSynchronizationManager.isActualTransactionActive()) conflict();}
    private static void same(Object a,Object b) {if(!Objects.equals(a,b)) conflict();}
    private static void conflict() {throw BizException.of(ErrorCode.CONFLICT);}
    private static <T>T scoped(Supplier<T> operation) {return DataScopeContext.executeWithoutScope(operation);}
    private String write(Object value) {return json.writeValueAsString(value);}
    private <T>T read(String value,Class<T> type) {
        try {return json.readValue(value,type);}catch(Exception e) {throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
    }
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e) {throw new IllegalStateException("SHA-256 unavailable");}
    }
}
