package ai.neargo.shop.pay;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.port.PaymentReportFactsPortAdapter;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.DayEvidence;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.settle.SettlePort.PaymentOpen;
import ai.neargo.shop.spi.settle.SettlePort.PaymentStore;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentReportFactsServiceTest {
    @Test void refundRequestAmountCannotBecomeAVerifiedRefund() {
        var payments=mock(PaymentMapper.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var refund=payment();refund.setDirection(StlPayment.REFUND);refund.setOriginPaymentNo("P0");
        var origin=payment();origin.setPaymentNo("P0");origin.setPaymentRequestJson(request("S1"));
        when(payments.selectList(any())).thenReturn(List.of(refund));when(payments.selectOne(any())).thenReturn(origin);
        var service=new PaymentReportFactsPortAdapter(payments,manager);var result=service.readSnapshot(query());
        var amount=result.facts().stream().filter(f->f.metricCode().equals("REFUND_SUCCESS_AMOUNT")).findFirst().orElseThrow().value();
        assertThat(amount.amountMinor()).isNull();assertThat(amount.coverage()).isEqualTo(Coverage.UNKNOWN);
        assertThat(result.coverage()).isEqualTo(Coverage.PARTIAL);
    }
    @Test void anotherStoresFrozenRouteIsNotAttributedToThisStore() {
        var payments=mock(PaymentMapper.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var payment=payment();payment.setPaymentRequestJson(request("S2"));when(payments.selectList(any())).thenReturn(List.of(payment));
        var result=new PaymentReportFactsPortAdapter(payments,manager).readSnapshot(query());
        assertThat(result.facts()).isEmpty();assertThat(result.coverage()).isEqualTo(Coverage.PARTIAL);
    }
    private StlPayment payment() {
        var p=new StlPayment();p.setPaymentNo("P1");p.setDirection(StlPayment.PAY);p.setOrderNo("O1");p.setCurrency("AED");
        p.setPayChannel("CASH");p.setPayMethod("CASH");p.setAmountMinor(1000L);p.setSucceededAt(1L);return p;
    }
    private String request(String store) {
        var route=new PaymentRoute("E1",store,"PM1","CASH","SUB1","V1","DIRECT");
        return JsonMapper.builder().build().writeValueAsString(new PaymentOpen("O1","U1","E1","CASH",1000,"meal","AED",List.of(new PaymentStore("SO1",route))));
    }
    private SourceQuery query() {
        var start=Instant.parse("2020-01-01T00:00:00Z");
        return new SourceQuery(new Partition(new Scope("MAIN","E1","S1"),LocalDate.of(2020,1,1),1),
                new DayEvidence("D1","P1",0,"UTC",0,start,start.plusSeconds(86400)),"ACTOR",List.of(),100);
    }
}
