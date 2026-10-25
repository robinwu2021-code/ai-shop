package ai.neargo.shop.trade.port;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.IndustryReportFactsService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name={"shop.report.enabled","shop.report.food.enabled"},havingValue="true")
public class IndustryReportFactsPortAdapter implements IndustryReportFactsService {
    private final IndustryOrderSourceMapper bindings;
    private final PaymentMapper orders;
    private final StoreOrderMapper subs;
    private final IndustryOrderAmendmentMapper amendments;
    private final MerchantScopeFactsPort scopes;
    private final TransactionTemplate read;
    public IndustryReportFactsPortAdapter(IndustryOrderSourceMapper bindings,PaymentMapper orders,StoreOrderMapper subs,
            IndustryOrderAmendmentMapper amendments,MerchantScopeFactsPort scopes,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.bindings=bindings; this.orders=orders; this.subs=subs; this.amendments=amendments; this.scopes=scopes;
        read=new TransactionTemplate(manager); read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public SourceKey source() { return new SourceKey(Owner.CORE,"ORDER"); }
    @Override public SourceSnapshot readSnapshot(SourceQuery query) {
        var s=query.partition().scope();
        if (!"MAIN".equals(s.tenantNo()) || !scopes.entityActive(s.tenantNo(),s.entityNo())
                || !scopes.storesOwned(s.tenantNo(),s.entityNo(),Set.of(s.storeNo())))
            throw new IllegalArgumentException("REPORT_STORE_NOT_OWNED");
        if (new HashSet<>(query.sourceRefs()).size()!=query.sourceRefs().size())
            throw new IllegalArgumentException("REPORT_DUPLICATE_SOURCE_REFERENCE");
        return Objects.requireNonNull(read.execute(tx->DataScopeContext.executeWithoutScope(()->collect(query))));
    }
    private SourceSnapshot collect(SourceQuery q) {
        Instant snapshotAt=Instant.now();
        var s=q.partition().scope();
        List<MetricFact> facts=new ArrayList<>(); List<Object> evidence=new ArrayList<>();
        long transacted=0; long completed=0;
        for (String ref:q.sourceRefs().stream().sorted().toList()) {
            var binding=bindings.selectOne(Wrappers.<OrdIndustryOrderSource>lambdaQuery()
                    .eq(OrdIndustryOrderSource::getTenantNo,s.tenantNo()).eq(OrdIndustryOrderSource::getEntityNo,s.entityNo())
                    .eq(OrdIndustryOrderSource::getStoreNo,s.storeNo()).eq(OrdIndustryOrderSource::getOrderNo,ref)
                    .eq(OrdIndustryOrderSource::getSourceSystem,"FOOD").eq(OrdIndustryOrderSource::getDeleted,0));
            if (binding==null || !Set.of("BOUND","ACCEPTED").contains(binding.getStatus())) unavailable();
            var order=orders.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getTenantNo,s.tenantNo())
                    .eq(OrdOrder::getOrderNo,ref).eq(OrdOrder::getDeleted,0));
            var sub=subs.selectOne(Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo,s.tenantNo())
                    .eq(OrdSubOrder::getEntityNo,s.entityNo()).eq(OrdSubOrder::getStoreNo,s.storeNo())
                    .eq(OrdSubOrder::getOrderNo,ref).eq(OrdSubOrder::getSubOrderNo,binding.getSubOrderNo()).eq(OrdSubOrder::getDeleted,0));
            if (order==null || sub==null || order.getPayAmount()==null || order.getCurrency()==null) unavailable();
            long change=amendments.receivableAdjustment(s.tenantNo(),s.entityNo(),ref);
            long actual=Math.addExact(order.getPayAmount(),change);
            if (actual<0) unavailable();
            // POSTPAY acceptance advances fulfillment before cash is received. Keep the original paid proof.
            boolean traded=order.getPaidAt()!=null && OrdSubOrder.TRANSACTED.contains(sub.getStatus());
            if (traded) transacted++;
            if (traded && OrdSubOrder.COMPLETED.equals(sub.getStatus())) completed++;
            facts.add(money("ORDER_ORIGINAL_DUE_AMOUNT",order.getPayAmount(),order.getCurrency()));
            facts.add(money("ORDER_EFFECTIVE_DUE_AMOUNT",actual,order.getCurrency()));
            facts.add(money("RECEIVABLE_ADJUSTMENT_AMOUNT",change,order.getCurrency()));
            facts.add(money("ORDER_TRANSACTED_AMOUNT",traded?actual:0,order.getCurrency()));
            evidence.add(List.of(ref,binding.getSubOrderNo(),Objects.toString(binding.getSourceVersion(),""),
                    binding.getStatus(),order.getStatus(),sub.getStatus(),Objects.toString(order.getPaidAt(),""),
                    order.getCurrency(),order.getPayAmount(),actual,change));
        }
        facts.add(count("ORDER_CREATED_COUNT",q.sourceRefs().size()));
        facts.add(count("ORDER_TRANSACTED_COUNT",transacted)); facts.add(count("ORDER_COMPLETED_COUNT",completed));
        String digest=PricingSnapshotHash.of(List.of(q.partition(),q.day(),evidence));
        return new SourceSnapshot(q.partition(),source(),digest,digest,snapshotAt,q.sourceRefs().size(),digest,
                q.sourceRefs().isEmpty()?Coverage.EMPTY_VERIFIED:Coverage.COMPLETE,null,facts,q.sourceRefs());
    }
    private static MetricFact count(String code,long n) {
        return new MetricFact(code,"OPERATING_ORDER_DAY",Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),MetricValue.count(n));
    }
    private static MetricFact money(String code,long n,String currency) {
        return new MetricFact(code,"OPERATING_ORDER_DAY",Attribution.ORIGINAL_ORDER,Map.of(),Map.of(),MetricValue.money(n,currency));
    }
    private static void unavailable() { throw new IllegalStateException("REPORT_CORE_ORDER_EVIDENCE_UNAVAILABLE"); }
}
