package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.support.BasicRefundFixture;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;

/** Existing historical refund rows remain queryable but cannot authorize a new cash attempt for the same case. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-preparation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class RefundPreparationConflictFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentMapper payments;
    @Autowired private RefundExecutionService refunds;
    @MockitoSpyBean private TestPayGateway gateway;

    @Test
    void knownUndispatchedFullRefundCapConflictIsFailedRatherThanUnknown() throws Exception {
        try (var f = new BasicRefundFixture(context)) {
            var purchase = f.purchase("13207071801");
            String no = f.apply(purchase, "REFUND_ONLY");
            historical(purchase, no);
            f.approve(purchase, no);
            assertThat(jdbc.queryForObject("select status from ord_refund_execution where after_sale_no=?",
                    String.class, no)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select error_code from ord_refund_execution where after_sale_no=?",
                    String.class, no)).isEqualTo("REFUND_PREPARATION_CONFLICT");
            assertThat(jdbc.queryForObject("select count(*) from stl_payment where after_sale_no=?"
                    + " and direction='REFUND'", Long.class, no)).isEqualTo(1L);
            verify(gateway, never()).refund(any(), anyLong(), anyString(), anyString());
        }
    }

    @Test
    void legacySameCaseForbidsNewExecutionEvenWhenOriginalPaymentHasRemainingCapacity() throws Exception {
        try (var f = new BasicRefundFixture(context)) {
            var purchase = f.purchase("13207071802");
            String no = f.apply(purchase, "REFUND_ONLY");
            StlPayment origin = historical(purchase, no);
            var result = refunds.request(new RefundRequest("RF-" + no, no, purchase.subNo(), origin.getPaymentNo(),
                    origin.getAmountMinor() - 1, origin.getCurrency(), "historical conflict"));
            assertThat(result.status()).isEqualTo(DispatchStatus.FAILED);
            assertThat(result.errorCode()).isEqualTo("REFUND_PREPARATION_CONFLICT");
            assertThat(jdbc.queryForObject("select count(*) from stl_payment where after_sale_no=?"
                    + " and direction='REFUND'", Long.class, no)).isEqualTo(1L);
            verify(gateway, never()).refund(any(), anyLong(), anyString(), anyString());
        }
    }

    private StlPayment historical(BasicRefundFixture.Purchase p, String afterSale) {
        StlPayment origin = payments.selectOne(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getOrderNo, p.payNo()).eq(StlPayment::getDirection, StlPayment.PAY));
        StlPayment old = new StlPayment();
        old.setPaymentNo("OLD-" + UUID.randomUUID());
        old.setOutTradeNo(old.getPaymentNo());
        old.setOrderNo(p.payNo());
        old.setSubOrderNo(p.subNo());
        old.setAfterSaleNo(afterSale);
        old.setDirection(StlPayment.REFUND);
        old.setStatus(StlPayment.PENDING);
        old.setUserNo(origin.getUserNo());
        old.setPayChannel(origin.getPayChannel());
        old.setCurrency(origin.getCurrency());
        old.setAmountMinor(1L);
        payments.insert(old);
        return origin;
    }
}
