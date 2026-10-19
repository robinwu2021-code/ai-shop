package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verified refund cash preserves the original acceptance receipt and rejects actual amount mismatches. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:refund_cash_evidence;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class RefundCashEvidenceTest {
    @Autowired private PaymentLedgerService ledger;
    @Autowired @Qualifier("payDataSource") private DataSource dataSource;

    @Test void queried_refund_amount_must_match_before_success_or_proof() {
        String no=fixture("ACCEPTED");
        assertThatThrownBy(() -> ledger.markRefundSettled(no,"QUERY_RECEIPT",49))
                .hasMessage("PAY_REFUND_VERIFIED_AMOUNT_MISMATCH");
        assertThat(jdbc().queryForObject("SELECT status FROM stl_payment WHERE payment_no=?",String.class,no))
                .isEqualTo("PENDING");
        ledger.markRefundSettled(no,"QUERY_RECEIPT:"+no,50);
        var row=jdbc().queryForMap("SELECT * FROM stl_payment WHERE payment_no=?",no);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number)row.get("refund_verified_minor")).longValue()).isEqualTo(50);
        assertThat(row.get("refund_verified_provider_no")).isEqualTo("QUERY_RECEIPT:"+no);
        assertThat(row.get("trade_no")).isEqualTo("ACCEPTED:"+no);
        ledger.markRefundSettled(no,"QUERY_RECEIPT:"+no,50);
        assertThat(jdbc().queryForObject("SELECT refund_verified_at FROM stl_payment WHERE payment_no=?",
                Long.class,no)).isEqualTo(((Number)row.get("refund_verified_at")).longValue());
        assertThatThrownBy(() -> ledger.markRefundSettled(no,"QUERY_RECEIPT:"+no,49))
                .hasMessage("PAY_REFUND_VERIFIED_AMOUNT_MISMATCH");
    }

    @Test void lost_acceptance_reply_can_be_verified_without_overwriting_trade_no() {
        String no=fixture(null);
        ledger.markRefundSettled(no,"QUERY_RECEIPT:"+no,50);
        assertThat(jdbc().queryForObject("SELECT status FROM stl_payment WHERE payment_no=?",String.class,no))
                .isEqualTo("SUCCESS");
        assertThat(jdbc().queryForObject("SELECT trade_no FROM stl_payment WHERE payment_no=?",String.class,no))
                .isNull();
        assertThat(jdbc().queryForObject("SELECT refund_verified_provider_no FROM stl_payment WHERE payment_no=?",
                String.class,no)).isEqualTo("QUERY_RECEIPT:"+no);
    }
    private String fixture(String accepted) {
        String no="RF"+UUID.randomUUID().toString().replace("-", "");
        jdbc().update("INSERT INTO stl_payment(payment_no,direction,order_no,sub_order_no,after_sale_no,user_no,"
                + "amount_minor,currency,status,pay_channel,out_trade_no,trade_no,created_at,updated_at)"
                + " VALUES(?,'REFUND',?,?,?,'USER',50,'CNY','PENDING','TEST',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",no,"ORDER:"+no,"SUB:"+no,
                "AS:"+no,"OUT:"+no,accepted==null?null:accepted+":"+no);
        return no;
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
}
