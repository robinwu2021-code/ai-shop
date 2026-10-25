package ai.neargo.shop.food.port;

import java.time.Instant;
import ai.neargo.shop.food.service.impl.BusinessCloseServiceImpl;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.entity.FnbBusinessDay;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.mapper.FoodMappers.BusinessDayMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.service.DiningReportFactsService;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.DayKind;
import java.time.ZoneId;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.user.StoreCardQueryPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded, repeatable local source scan. No CORE/PAY calls while the FOOD snapshot is held. */
public final class DiningReportFactsPortAdapter implements DiningReportFactsService {
    private final BusinessDayMapper days;
    private final StorePolicyMapper policies;
    private final OrderBatchMapper batches;
    private final CoreOrderBindingMapper bindings;
    private final OrderLineMapper lines;
    private final StoreCardQueryPort stores;
    private final TransactionTemplate read;
    public DiningReportFactsPortAdapter(BusinessDayMapper days,StorePolicyMapper policies,OrderBatchMapper batches,
            CoreOrderBindingMapper bindings,OrderLineMapper lines,StoreCardQueryPort stores,PlatformTransactionManager manager) {
        this.days=days; this.policies=policies; this.batches=batches; this.bindings=bindings; this.lines=lines; this.stores=stores;
        read=new TransactionTemplate(manager); read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public List<AffectedDay> affectedDays(Change change) {
        if (!"MAIN".equals(change.tenantNo())) throw new IllegalArgumentException("REPORT_STORE_NOT_OWNED");
        var original=Objects.requireNonNull(read.execute(tx->DataScopeContext.executeWithoutScope(()-> {
            var query=Wrappers.<FnbCoreOrderBinding>lambdaQuery().eq(FnbCoreOrderBinding::getTenantNo,change.tenantNo())
                    .eq(FnbCoreOrderBinding::getDeleted,0).eq(FnbCoreOrderBinding::getStatus,"BOUND");
            if(change.orderNo()!=null) query.eq(FnbCoreOrderBinding::getPaymentRootNo,change.orderNo());
            if(change.subOrderNo()!=null) query.eq(FnbCoreOrderBinding::getStoreOrderNo,change.subOrderNo());
            var links=bindings.selectList(query.last("LIMIT 101"));
            if(links.size()>100) throw new IllegalStateException("REPORT_CHANGE_SCOPE_LIMIT");
            var result=new java.util.LinkedHashSet<AffectedDay>();
            for(var link:links) {
                var found=batches.selectList(Wrappers.<FnbOrderBatch>lambdaQuery()
                        .eq(FnbOrderBatch::getTenantNo,change.tenantNo()).eq(FnbOrderBatch::getEntityNo,link.getEntityNo())
                        .eq(FnbOrderBatch::getBatchNo,link.getBatchNo()).eq(FnbOrderBatch::getDeleted,0).last("LIMIT 2"));
                if(found.size()!=1) throw new IllegalStateException("REPORT_CHANGE_BINDING_UNRESOLVED");
                var batch=found.getFirst();
                result.add(new AffectedDay(new Scope(change.tenantNo(),batch.getEntityNo(),batch.getStoreNo()),batch.getBusinessDate()));
            }
            return List.copyOf(result);
        })));
        var result=new java.util.LinkedHashSet<>(original);
        for(var scope:original.stream().map(AffectedDay::scope).distinct().toList()) {
            authorize(scope);
            // UTC +/-2 covers all legal time zones and a full-day business cut. Reuse original day evidence.
            var utcDate=change.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
            boolean located=false;
            for(int offset:new int[]{0,-1,1,-2,2}) {
                var date=utcDate.plusDays(offset);DayEvidence evidence;
                try {evidence=day(scope,date);}
                catch(IllegalStateException unavailable) {
                    if("REPORT_ORIGINAL_POLICY_NOT_READY".equals(unavailable.getMessage())) continue;
                    throw unavailable;
                }
                if(!change.occurredAt().isBefore(evidence.startsAt()) && change.occurredAt().isBefore(evidence.endsAt())) {
                    result.add(new AffectedDay(scope,date));located=true;break;
                }
            }
            if(!located) throw new IllegalStateException("REPORT_CHANGE_DAY_UNRESOLVED");
        }
        return List.copyOf(result);
    }
    @Override public SourceKey source() { return new SourceKey(Owner.FOOD,"ORDER"); }
    @Override public OperatingDay current(Scope scope) {
        authorize(scope);
        return Objects.requireNonNull(read.execute(tx->DataScopeContext.executeWithoutScope(()-> {
            var now=LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC);
            var selected=days.selectList(Wrappers.<FnbBusinessDay>lambdaQuery().eq(FnbBusinessDay::getTenantNo,scope.tenantNo())
                    .eq(FnbBusinessDay::getEntityNo,scope.entityNo()).eq(FnbBusinessDay::getStoreNo,scope.storeNo())
                    .le(FnbBusinessDay::getStartsAt,now).gt(FnbBusinessDay::getEndsAt,now).eq(FnbBusinessDay::getDeleted,0).last("LIMIT 2"));
            if (selected.size()>1) throw new IllegalStateException("REPORT_CURRENT_DAY_AMBIGUOUS");
            if (selected.isEmpty()) {
                var candidates=policyRows(scope);
                for(var policy:candidates) {
                    if(policy.getEffectiveFrom().isAfter(now)) continue;
                    var local=Instant.now().atZone(ZoneId.of(policy.getTimezone()));
                    var date=local.toLocalDate();
                    if(local.getHour()*60+local.getMinute()<policy.getCutoverMinute()) date=date.minusDays(1);
                    var interval=dayInside(scope,date);
                    if(!Instant.now().isBefore(interval.startsAt())&&Instant.now().isBefore(interval.endsAt())) return new OperatingDay(date,interval);
                }
                throw new IllegalStateException("REPORT_CURRENT_DAY_NOT_READY");
            }
            var date=selected.getFirst().getBusinessDate();
            return new OperatingDay(date,dayInside(scope,date));
        })));
    }
    @Override public DayEvidence day(Scope scope,LocalDate date) {
        authorize(scope);
        return Objects.requireNonNull(read.execute(s->DataScopeContext.executeWithoutScope(()->dayInside(scope,date))));
    }
    private DayEvidence dayInside(Scope s,LocalDate date) {
        var rows=days.selectList(Wrappers.<FnbBusinessDay>lambdaQuery().eq(FnbBusinessDay::getTenantNo,s.tenantNo())
                .eq(FnbBusinessDay::getEntityNo,s.entityNo()).eq(FnbBusinessDay::getStoreNo,s.storeNo())
                .eq(FnbBusinessDay::getBusinessDate,date).eq(FnbBusinessDay::getDeleted,0).last("LIMIT 2"));
        if (rows.size()>1) throw new IllegalStateException("REPORT_ORIGINAL_DAY_AMBIGUOUS");
        if (rows.isEmpty()) return policyInterval(s,date);
        var day=rows.getFirst();
        var rowsPolicy=policies.selectList(Wrappers.<FnbStorePolicy>lambdaQuery().eq(FnbStorePolicy::getTenantNo,s.tenantNo())
                .eq(FnbStorePolicy::getEntityNo,s.entityNo()).eq(FnbStorePolicy::getStoreNo,s.storeNo())
                .eq(FnbStorePolicy::getPolicyNo,day.getPolicyNo()).eq(FnbStorePolicy::getDeleted,0).last("LIMIT 2"));
        if (rowsPolicy.size()!=1) throw new IllegalStateException("REPORT_ORIGINAL_POLICY_NOT_READY");
        var policy=rowsPolicy.getFirst();
        return new DayEvidence(day.getDayNo(),day.getPolicyNo(),0,policy.getTimezone(),policy.getCutoverMinute(),
                day.getStartsAt().toInstant(ZoneOffset.UTC),day.getEndsAt().toInstant(ZoneOffset.UTC));
    }
    private List<FnbStorePolicy> policyRows(Scope scope) {
        var selected=policies.selectList(Wrappers.<FnbStorePolicy>lambdaQuery().eq(FnbStorePolicy::getTenantNo,scope.tenantNo())
                .eq(FnbStorePolicy::getEntityNo,scope.entityNo()).eq(FnbStorePolicy::getStoreNo,scope.storeNo())
                .eq(FnbStorePolicy::getDeleted,0).orderByDesc(FnbStorePolicy::getEffectiveFrom).orderByDesc(FnbStorePolicy::getPolicyNo).last("LIMIT 257"));
        if(selected.size()>256) throw new IllegalStateException("REPORT_POLICY_HISTORY_LIMIT");
        return selected;
    }
    private DayEvidence policyInterval(Scope scope,LocalDate date) {
        // Same original policy-at-local-day-start rule and DST boundary calculation as business-day opening.
        var policy=policyRows(scope).stream().filter(p->!p.getEffectiveFrom().isAfter(
                BusinessCloseServiceImpl.boundaries(date,p.getTimezone(),p.getCutoverMinute()).getFirst()))
                .findFirst().orElseThrow(()->new IllegalStateException("REPORT_ORIGINAL_POLICY_NOT_READY"));
        var bounds=BusinessCloseServiceImpl.boundaries(date,policy.getTimezone(),policy.getCutoverMinute());
        String reference="POLICY:"+PricingSnapshotHash.of(List.of(scope,date,policy.getPolicyNo())).substring(0,32);
        return new DayEvidence(reference,policy.getPolicyNo(),0,policy.getTimezone(),policy.getCutoverMinute(),
                bounds.getFirst().toInstant(ZoneOffset.UTC),bounds.getLast().toInstant(ZoneOffset.UTC),DayKind.POLICY_INTERVAL);
    }
    @Override public SourceSnapshot readSnapshot(SourceQuery query) {
        authorize(query.partition().scope());
        return Objects.requireNonNull(read.execute(s->DataScopeContext.executeWithoutScope(()->collect(query))));
    }
    private SourceSnapshot collect(SourceQuery q) {
        Instant snapshotAt=Instant.now();
        Scope s=q.partition().scope();
        if (!q.day().accepts(dayInside(s,q.partition().businessDate()))) throw new IllegalStateException("REPORT_DAY_EVIDENCE_CHANGED");
        var originals=batches.selectList(Wrappers.<FnbOrderBatch>lambdaQuery().eq(FnbOrderBatch::getTenantNo,s.tenantNo())
                .eq(FnbOrderBatch::getEntityNo,s.entityNo()).eq(FnbOrderBatch::getStoreNo,s.storeNo())
                .eq(FnbOrderBatch::getBusinessDate,q.partition().businessDate()).eq(FnbOrderBatch::getDeleted,0)
                .orderByAsc(FnbOrderBatch::getBatchNo).last("LIMIT "+(q.rowLimit()+1)));
        boolean unresolved=false;
        int scanned=originals.size(); if (scanned>q.rowLimit()) limit();
        List<MetricFact> facts=new ArrayList<>(); List<Object> evidence=new ArrayList<>(); TreeSet<String> refs=new TreeSet<>();
        facts.add(count("ORDER_BATCH_COUNT",originals.size()));
        facts.add(count("BATCH_ACCEPTED_EVENT_COUNT",originals.stream().filter(b->b.getAcceptedAt()!=null).count()));
        for (var batch:originals) {
            evidence.add(List.of(batch.getBatchNo(),Objects.toString(batch.getStatus(),""),Objects.toString(batch.getAcceptedAt(),"")));
            var linked=bindings.selectList(Wrappers.<FnbCoreOrderBinding>lambdaQuery().eq(FnbCoreOrderBinding::getTenantNo,s.tenantNo())
                    .eq(FnbCoreOrderBinding::getEntityNo,s.entityNo()).eq(FnbCoreOrderBinding::getBatchNo,batch.getBatchNo())
                    .eq(FnbCoreOrderBinding::getStatus,"BOUND").eq(FnbCoreOrderBinding::getDeleted,0).last("LIMIT 2"));
            if (linked.size()>1) throw new IllegalStateException("REPORT_DUPLICATE_CORE_BINDING");
            boolean bound="BOUND".equals(batch.getStatus())||"ACCEPTED".equals(batch.getStatus());
            boolean cancelled="CANCELLED".equals(batch.getStatus());
            if ((!bound&&!cancelled) || bound&&linked.size()!=1 || cancelled&&!linked.isEmpty()
                    || "ACCEPTED".equals(batch.getStatus())&&batch.getAcceptedAt()==null) unresolved=true;
            scanned+=linked.size();
            for (var link:linked) {
                if (!refs.add(link.getPaymentRootNo())) throw new IllegalStateException("REPORT_DUPLICATE_CORE_ORDER");
                evidence.add(List.of(link.getBindingNo(),link.getPaymentRootNo(),link.getStoreOrderNo()));
            }
            if (batch.getAcceptedAt()==null) continue;
            int remaining=q.rowLimit()-scanned;
            if (remaining<0) limit();
            var selected=lines.selectList(Wrappers.<FnbOrderLine>lambdaQuery().eq(FnbOrderLine::getTenantNo,s.tenantNo())
                    .eq(FnbOrderLine::getEntityNo,s.entityNo()).eq(FnbOrderLine::getBatchNo,batch.getBatchNo())
                    .eq(FnbOrderLine::getDeleted,0).orderByAsc(FnbOrderLine::getFoodLineNo).last("LIMIT "+(remaining+1)));
            scanned+=selected.size(); if (scanned>q.rowLimit()) limit();
            for (var line:selected) {
                if (line.getQty()==null || line.getUnit()==null) throw new IllegalStateException("REPORT_ORIGINAL_QUANTITY_NOT_READY");
                facts.add(new MetricFact("DISH_ORDERED_QTY","DISH_COMPONENT_DAY",Attribution.ORIGINAL_ORDER,
                        Map.of("skuNo",line.getSkuNo(),"componentRole","LINE"),Map.of(),MetricValue.quantity(line.getQty(),line.getUnit())));
                evidence.add(List.of(line.getFoodLineNo(),line.getSkuNo(),line.getQty(),line.getUnit()));
            }
        }
        String digest=PricingSnapshotHash.of(List.of(q.partition(),q.day(),evidence));
        return new SourceSnapshot(q.partition(),source(),digest,digest,snapshotAt,scanned,digest,
                unresolved?Coverage.PARTIAL:scanned==0?Coverage.EMPTY_VERIFIED:Coverage.COMPLETE,
                unresolved?"FOOD_ORDER_BINDING_UNRESOLVED":null,facts,List.copyOf(refs));
    }
    private void authorize(Scope scope) {
        if (!"MAIN".equals(scope.tenantNo())) throw new IllegalArgumentException("REPORT_SCOPE_UNSUPPORTED");
        var card=stores.cards(List.of(scope.storeNo())).get(scope.storeNo());
        if (card==null || !scope.entityNo().equals(card.entityNo())) throw new IllegalArgumentException("REPORT_STORE_NOT_OWNED");
    }
    private static MetricFact count(String code,long count) {
        return new MetricFact(code,"OPERATING_ORDER_DAY",Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),MetricValue.count(count));
    }
    private static void limit() { throw new IllegalStateException("REPORT_SOURCE_ROW_LIMIT"); }
}
