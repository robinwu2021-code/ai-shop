package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 退款回调认领真实资金行，付款与退款方向不能互相冒充。 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentLedgerRefundCallbackTest {

    @Autowired
    private PaymentLedgerService ledger;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void refundCallbackClaimsRefundAndPreservesFirstSuccess() {
        String no = insert(StlPayment.REFUND);

        assertThat(ledger.settleRefundByOutTradeNo(no, "provider-refund"))
                .isEqualTo("P-" + no);
        assertThat(status(no)).isEqualTo(StlPayment.SUCCESS);
        Long first = jdbc.queryForObject("select succeeded_at from stl_payment where out_trade_no = ?",
                Long.class, no);
        assertThat(first).isPositive();

        assertThat(ledger.settleRefundByOutTradeNo(no, "provider-refund-replay"))
                .isEqualTo("P-" + no);
        assertThat(jdbc.queryForObject("select succeeded_at from stl_payment where out_trade_no = ?",
                Long.class, no)).isEqualTo(first);
    }

    @Test
    void refundCallbackRejectsMissingAndPayDirectionRows() {
        String no = insert(StlPayment.PAY);

        assertThat(ledger.settleRefundByOutTradeNo("missing-refund-" + no, "provider")).isNull();
        assertThat(ledger.settleRefundByOutTradeNo(no, "provider")).isNull();
        assertThat(status(no)).isEqualTo(StlPayment.PENDING);
        assertThat(jdbc.queryForObject("select succeeded_at from stl_payment where out_trade_no = ?",
                Long.class, no)).isNull();
    }

    @Test
    void payerIdentityStillOnlyUpdatesPayDirection() {
        String pay = insert(StlPayment.PAY);
        String refund = insert(StlPayment.REFUND);

        ledger.recordPayer(pay, "buyer-openid", "buyer-app");
        ledger.recordPayer(refund, "wrong-openid", "wrong-app");

        assertThat(jdbc.queryForObject("select payer_openid from stl_payment where out_trade_no = ?",
                String.class, pay)).isEqualTo("buyer-openid");
        assertThat(jdbc.queryForObject("select payer_openid from stl_payment where out_trade_no = ?",
                String.class, refund)).isNull();
    }

    private String insert(String direction) {
        String no = "FC-" + UUID.randomUUID();
        jdbc.update("insert into stl_payment (payment_no, out_trade_no, order_no, direction, status, "
                        + "pay_channel, user_no, amount_minor, tenant_no, version, deleted, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'PENDING', 'STUB', 'U-CALLBACK', 1000, 'MAIN', 0, 0, now(), now())",
                "PAY-" + no, no, "P-" + no, direction);
        return no;
    }

    private String status(String no) {
        return jdbc.queryForObject("select status from stl_payment where out_trade_no = ?", String.class, no);
    }
}
