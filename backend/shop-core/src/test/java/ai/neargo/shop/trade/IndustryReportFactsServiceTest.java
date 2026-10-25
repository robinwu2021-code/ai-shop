package ai.neargo.shop.trade;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.IndustryOrderAmendmentMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.port.IndustryReportFactsPortAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndustryReportFactsServiceTest {
    private final IndustryOrderSourceMapper bindings=mock(IndustryOrderSourceMapper.class);
    private final PaymentMapper orders=mock(PaymentMapper.class);
    private final StoreOrderMapper subs=mock(StoreOrderMapper.class);
    private final IndustryOrderAmendmentMapper amendments=mock(IndustryOrderAmendmentMapper.class);
    private final MerchantScopeFactsPort scopes=mock(MerchantScopeFactsPort.class);
    private final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
    private IndustryReportFactsPortAdapter service;
    private OrdOrder order;
    @BeforeEach void setup() {
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(scopes.entityActive(anyString(),anyString())).thenReturn(true);
        when(scopes.storesOwned(anyString(),anyString(),any())).thenReturn(true);
        var binding=new OrdIndustryOrderSource(); binding.setOrderNo("O1"); binding.setSubOrderNo("SO1");
        binding.setStatus("ACCEPTED"); binding.setSourceVersion(1L);
        when(bindings.selectOne(any())).thenReturn(binding);
        order=new OrdOrder(); order.setOrderNo("O1"); order.setStatus(OrdOrder.WAIT_PAY);
        order.setPayAmount(1000L); order.setCurrency("AED");
        when(orders.selectOne(any())).thenReturn(order);
        var sub=new OrdSubOrder(); sub.setStatus(OrdSubOrder.WAIT_FULFILL);
        when(subs.selectOne(any())).thenReturn(sub);
        service=new IndustryReportFactsPortAdapter(bindings,orders,subs,amendments,scopes,tx);
    }
    @Test void unpaidPostpayAcceptanceIsNotATrade() {
        var snapshot=service.readSnapshot(query());
        assertThat(snapshot.facts().stream().filter(f->f.metricCode().equals("ORDER_TRANSACTED_COUNT")).findFirst().orElseThrow().value().integerValue()).isZero();
        assertThat(snapshot.facts().stream().filter(f->f.metricCode().equals("ORDER_TRANSACTED_AMOUNT")).findFirst().orElseThrow().value().amountMinor()).isZero();
    }
    @Test void paidOrderUsesOriginalAndEffectiveReceivables() {
        order.setStatus(OrdOrder.PAID); order.setPaidAt(1L);
        when(amendments.receivableAdjustment("MAIN","E1","O1")).thenReturn(-200L);
        var snapshot=service.readSnapshot(query());
        assertThat(snapshot.facts().stream().filter(f->f.metricCode().equals("ORDER_TRANSACTED_AMOUNT")).findFirst().orElseThrow().value().amountMinor()).isEqualTo(800L);
        assertThat(snapshot.facts().stream().filter(f->f.metricCode().equals("ORDER_ORIGINAL_DUE_AMOUNT")).findFirst().orElseThrow().value().amountMinor()).isEqualTo(1000L);
    }
    @Test void foreignStoreOrInvalidReceivableCannotProduceFacts() {
        when(scopes.storesOwned(anyString(),anyString(),any())).thenReturn(false);
        assertThatThrownBy(()->service.readSnapshot(query())).hasMessage("REPORT_STORE_NOT_OWNED");
        when(scopes.storesOwned(anyString(),anyString(),any())).thenReturn(true);
        when(amendments.receivableAdjustment("MAIN","E1","O1")).thenReturn(-1001L);
        assertThatThrownBy(()->service.readSnapshot(query())).hasMessage("REPORT_CORE_ORDER_EVIDENCE_UNAVAILABLE");
    }
    private SourceQuery query() {
        var start=Instant.parse("2026-10-20T00:00:00Z");
        return new SourceQuery(new Partition(new Scope("MAIN","E1","S1"),LocalDate.of(2026,10,20),1),
                new DayEvidence("D1","P1",0,"UTC",0,start,start.plusSeconds(86400)),"ACTOR",List.of("O1"),100);
    }
}
