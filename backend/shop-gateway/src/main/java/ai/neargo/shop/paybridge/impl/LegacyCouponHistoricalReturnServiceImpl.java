package ai.neargo.shop.paybridge.impl;
import ai.neargo.shop.trade.service.LegacyCouponHistoricalReturnService;
import ai.neargo.shop.trade.service.LegacyCouponOrderReturnFacts;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.LegacyCouponSourceCommitMapper;
import ai.neargo.shop.promotion.migration.PmtMigrationDecision;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnSourcePort.Fact;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import ai.neargo.shop.trade.entity.PmtMigrationReturn;
import ai.neargo.shop.paybridge.mapper.LegacyCouponHistoricalReturnMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
/** Actual old-order closure creates a new immutable decision; original frozen pmt_apply rows are never rewritten. */
@Service("legacyCouponHistoricalReturnService")
public class LegacyCouponHistoricalReturnServiceImpl implements LegacyCouponHistoricalReturnService {
    private final LegacyCouponHistoricalReturnMapper rows;
    private final LegacyCouponSourceCommitMapper commits;
    private final LegacyCouponOrderReturnFacts orders;
    private final ObjectProvider<PlatformSwitchPort> switches;
    private final OutboxEventBus events;
    private final ObjectMapper json;
    public LegacyCouponHistoricalReturnServiceImpl(LegacyCouponHistoricalReturnMapper rows,LegacyCouponSourceCommitMapper commits,
            LegacyCouponOrderReturnFacts orders,ObjectProvider<PlatformSwitchPort> switches,OutboxEventBus events,ObjectMapper json) {
        this.rows=rows;this.commits=commits;this.orders=orders;this.switches=switches;this.events=events;this.json=json;
    }
    @Transactional
    public boolean cancelled(EntitlementContext ctx,String orderNo) {
        check(ctx,orderNo);return DataScopeContext.executeWithoutScope(()->{
            var candidates=candidates(ctx,orderNo);if(candidates.isEmpty()) return false;
            orders.cancelled(ctx,orderNo).ifPresent(facts->record(ctx,candidates,facts,true));return true;
        });
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public List<Fact> refunded(EntitlementContext ctx,String afterSaleNo) {
        check(ctx,afterSaleNo);return DataScopeContext.executeWithoutScope(()->{
            var facts=orders.refunded(ctx,afterSaleNo);if(facts.isEmpty()) return List.of();
            var candidates=candidates(ctx,facts.get().orderNo());if(candidates.isEmpty()) return List.of();
            var policy=switches.getIfAvailable();boolean allowed=policy==null || policy.bool("refund.return-coupon",true);
            return record(ctx,candidates,facts.get(),allowed);
        });
    }
    private record Candidate(PmtApply usage,PmtUserCoupon instance,PmtMigrationDecision commit) { }
    private List<Candidate> candidates(EntitlementContext ctx,String no) {
        var result=new ArrayList<Candidate>();
        for(var use:rows.originalUses(ctx.tenantNo(),no)) {
            if(use.getRevertedAt()!=null) continue;
            var instance=rows.originalInstance(ctx.tenantNo(),use.getPromoNo());
            if(instance==null || Integer.valueOf(1).equals(instance.getDeleted())) continue;
            var commit=commits.findSource(ctx.tenantNo(),"PMT",instance.getCouponNo());if(commit==null) continue;
            if(!Objects.equals(instance.getUserNo(),use.getUserNo()) || !Objects.equals(instance.getEntityNo(),use.getEntityNo())
                    || !Objects.equals(commit.getEntityNo(),use.getEntityNo()) || use.getAmountMinor()==null || use.getAmountMinor()<0) conflict();
            result.add(new Candidate(use,instance,commit));
        }
        return result;
    }
    private List<Fact> record(EntitlementContext ctx,List<Candidate> candidates,LegacyCouponOrderReturnFacts.Eligible order,boolean allowed) {
        var result=new ArrayList<Fact>();
        for(var candidate:candidates) {
            var use=candidate.usage();
            if(!order.userNo().equals(use.getUserNo()) || !"CNY".equals(order.currency())) conflict();
            var old=rows.lockSource(ctx.tenantNo(),use.getApplyNo());
            if(old!=null) {result.add(fact(old));continue;}
            if(!allowed) continue;
            var at=Instant.now().truncatedTo(ChronoUnit.MILLIS);var commit=candidate.commit();
            String no="HR"+ActivityCommandDeduplicator.hash(ctx.tenantNo()+":"+use.getApplyNo()).substring(0,62);
            var f=new Fact(1,ctx.tenantNo(),no,use.getApplyNo(),use.getPromoNo(),commit.getDecisionNo(),commit.getMigrationBatchNo(),
                    commit.getPlanHash(),use.getUserNo(),use.getEntityNo(),order.orderNo(),order.sourceRefNo(),order.reason(),use.getAmountMinor(),"CNY",
                    LegacyCouponHistoryFacts.hash(json,use),at);
            var row=new PmtMigrationReturn();row.setTenantNo(ctx.tenantNo());row.setVersion(0L);row.setDeleted(0);
            row.setCreatedAt(LocalDateTime.ofInstant(at,ZoneOffset.UTC));row.setUpdatedAt(row.getCreatedAt());row.setCreatedBy(ctx.actorNo());row.setUpdatedBy(ctx.actorNo());
            row.setReturnNo(no);row.setSourceUsageNo(use.getApplyNo());row.setEntityNo(use.getEntityNo());row.setOrderNo(order.orderNo());
            row.setFactSnapshot(json.writeValueAsString(f));row.setStatus("COMMITTED");row.setPayState("PENDING");rows.insert(row);
            events.publish(new LegacyCouponHistoryEvents.ReturnDecided(ctx.tenantNo(),use.getEntityNo(),no));result.add(f);
        }
        return List.copyOf(result);
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Fact resolve(EntitlementContext ctx,String no) {
        check(ctx,no);return DataScopeContext.executeWithoutScope(()->{
            var row=rows.selectOne(Wrappers.<PmtMigrationReturn>lambdaQuery().eq(PmtMigrationReturn::getTenantNo,ctx.tenantNo()).eq(PmtMigrationReturn::getReturnNo,no));
            if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);return fact(row);
        });
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void acknowledge(EntitlementContext ctx,LegacyCouponHistoricalReturnPort.Receipt receipt) {
        check(ctx,receipt.returnNo());DataScopeContext.executeWithoutScope(()->{
            var row=rows.lockReturn(ctx.tenantNo(),receipt.returnNo());if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);var f=fact(row);
            if(!f.sourceUsageNo().equals(receipt.sourceUsageNo()) || !f.userNo().equals(receipt.userNo())
                    || !f.entityNo().equals(receipt.entityNo()) || !f.sourceHash().equals(receipt.sourceHash())) conflict();
            if("RESTORED".equals(row.getPayState())) {if(!receipt.receiptNo().equals(row.getPayReceiptNo())) conflict();return;}
            if(rows.acknowledge(ctx.tenantNo(),receipt.returnNo(),row.getVersion(),receipt.receiptNo(),LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS),ctx.actorNo())!=1) conflict();
        });
    }
    public String returnedTitle(String tenant,String orderNo) {
        return DataScopeContext.executeWithoutScope(()->{
            var row=rows.selectOne(Wrappers.<PmtMigrationReturn>lambdaQuery().eq(PmtMigrationReturn::getTenantNo,tenant)
                    .eq(PmtMigrationReturn::getOrderNo,orderNo).eq(PmtMigrationReturn::getPayState,"RESTORED").orderByAsc(PmtMigrationReturn::getId).last("LIMIT 1"));
            return row==null?null:rows.originalTitle(tenant,fact(row).sourceInstanceNo());
        });
    }
    private Fact fact(PmtMigrationReturn row) {return json.readValue(row.getFactSnapshot(),Fact.class);}
    private static void check(EntitlementContext ctx,String no) {ContractChecks.present(ctx);ContractChecks.id(no);if(!"MAIN".equals(ctx.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
    private static void conflict() {throw BizException.of(ErrorCode.CONFLICT);}
}
