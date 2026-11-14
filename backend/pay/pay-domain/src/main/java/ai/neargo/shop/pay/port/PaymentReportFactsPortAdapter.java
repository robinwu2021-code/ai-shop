package ai.neargo.shop.pay.port;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.service.PaymentReportFactsService;
import ai.neargo.shop.spi.report.ReportContracts.Attribution;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.MetricValue;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportContracts.ValueType;
import ai.neargo.shop.spi.settle.SettlePort.PaymentOpen;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Ledger reads only. Older unscoped cash is not silently declared to belong to this store. */
@Component
@ConditionalOnProperty(name="shop.pay.report-facts.enabled",havingValue="true")
public class PaymentReportFactsPortAdapter implements PaymentReportFactsService {
    private final PaymentMapper payments;
    private final TransactionTemplate read;
    private final JsonMapper json=JsonMapper.builder().build();
    public PaymentReportFactsPortAdapter(PaymentMapper payments,@Qualifier("payTxManager") PlatformTransactionManager manager) {
        this.payments=payments; read=new TransactionTemplate(manager); read.setReadOnly(true);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public SourceKey source() { return new SourceKey(Owner.PAY,"PAYMENT"); }
    @Override public SourceSnapshot readSnapshot(SourceQuery query) {
        if (!"MAIN".equals(query.partition().scope().tenantNo())) throw new IllegalArgumentException("REPORT_SCOPE_UNSUPPORTED");
        return Objects.requireNonNull(read.execute(tx->DataScopeContext.executeWithoutScope(()->collect(query))));
    }
    private SourceSnapshot collect(SourceQuery q) {
        Instant snapshotAt=Instant.now();
        var scope=q.partition().scope();
        // This scoped scan is intentionally PARTIAL: historical rows without entity/store evidence exist.
        var rows=payments.selectList(Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getTenantNo,scope.tenantNo())
                .eq(StlPayment::getEntityNo,scope.entityNo()).eq(StlPayment::getStatus,StlPayment.SUCCESS)
                .in(StlPayment::getDirection,List.of(StlPayment.PAY,StlPayment.REFUND))
                .ge(StlPayment::getSucceededAt,q.day().startsAt().toEpochMilli())
                .lt(StlPayment::getSucceededAt,q.day().endsAt().toEpochMilli())
                .eq(StlPayment::getDeleted,0).orderByAsc(StlPayment::getPaymentNo).last("LIMIT "+(q.rowLimit()+1)));
        if (rows.size()>q.rowLimit()) throw new IllegalStateException("REPORT_SOURCE_ROW_LIMIT");
        List<MetricFact> facts=new ArrayList<>(); List<Object> evidence=new ArrayList<>();
        for (var row:rows) {
            var origin=row;
            if (StlPayment.REFUND.equals(row.getDirection())) {
                if (row.getOriginPaymentNo()==null) continue;
                origin=payments.selectOne(Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getTenantNo,scope.tenantNo())
                        .eq(StlPayment::getEntityNo,scope.entityNo()).eq(StlPayment::getPaymentNo,row.getOriginPaymentNo())
                        .eq(StlPayment::getDirection,StlPayment.PAY).eq(StlPayment::getDeleted,0));
            }
            if (origin==null || origin.getPaymentRequestJson()==null) continue;
            PaymentOpen request;
            try { request=json.readValue(origin.getPaymentRequestJson(),PaymentOpen.class); }
            catch (RuntimeException malformed) { continue; }
            if (request.stores().isEmpty() || request.stores().stream().anyMatch(s->s.route()==null
                    || !scope.entityNo().equals(s.route().merchantNo()) || !scope.storeNo().equals(s.route().storeNo()))) continue;
            boolean refund=StlPayment.REFUND.equals(row.getDirection());
            var dimensions=refund?Map.of("paymentMethod",Objects.toString(origin.getPayMethod(),"UNKNOWN"),
                    "refundChannel",Objects.toString(row.getPayChannel(),"UNKNOWN")):
                    Map.of("paymentMethod",Objects.toString(row.getPayMethod(),"UNKNOWN"),"paymentChannel",Objects.toString(row.getPayChannel(),"UNKNOWN"));
            String set=refund?"REFUND_CHANNEL_DAY":"PAYMENT_CHANNEL_DAY";
            facts.add(fact(refund?"REFUND_SUCCESS_COUNT":"PAYMENT_SUCCESS_COUNT",set,dimensions,MetricValue.count(1)));
            Long amount=refund?row.getRefundVerifiedMinor():row.getAmountMinor();
            facts.add(fact(refund?"REFUND_SUCCESS_AMOUNT":"PAYMENT_SUCCESS_AMOUNT",set,dimensions,money(amount,row.getCurrency())));
            if (!refund) facts.add(fact("PAYMENT_FEE_AMOUNT",set,dimensions,money(row.getChannelFeeMinor(),row.getCurrency())));
            evidence.add(List.of(row.getPaymentNo(),row.getDirection(),row.getOrderNo(),row.getSucceededAt(),
                    Objects.toString(amount,"UNKNOWN"),Objects.toString(row.getCurrency(),"UNKNOWN"),
                    Objects.toString(row.getChannelFeeMinor(),"UNKNOWN"),dimensions));
        }
        String digest=PricingSnapshotHash.of(List.of(q.partition(),q.day(),evidence));
        return new SourceSnapshot(q.partition(),source(),digest,digest,snapshotAt,rows.size(),digest,Coverage.PARTIAL,
                "LEGACY_UNSCOPED_PAYMENT_COVERAGE",facts,List.of());
    }
    private static MetricValue money(Long amount,String currency) {
        if (amount==null || currency==null) return new MetricValue(ValueType.MONEY,null,null,null,currency,null,
                Coverage.UNKNOWN,"PAYMENT_AMOUNT_NOT_VERIFIED",null,null,null);
        return MetricValue.money(amount,currency);
    }
    private static MetricFact fact(String code,String set,Map<String,String> dimensions,MetricValue value) {
        return new MetricFact(code,set,Attribution.OCCURRENCE,dimensions,Map.of(),value);
    }
}
