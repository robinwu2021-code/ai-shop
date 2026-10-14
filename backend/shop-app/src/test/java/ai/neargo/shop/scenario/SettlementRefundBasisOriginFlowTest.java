package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.SettleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 原始生成与同事务重放不能误入已提交旧单的独立补录事务。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-basis;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class SettlementRefundBasisOriginFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettleService service;
    @Autowired @Qualifier("payTxManager") private PlatformTransactionManager payTx;

    private SettlementBasisFixtures fixture() { return new SettlementBasisFixtures(jdbc, service); }

    @Test
    void replayWithinSamePayTransactionCommitsOneBillAndBasis() {
        fixture().requireBasis();
        String no = fixture().payment();
        assertThatCode(() -> new TransactionTemplate(payTx).executeWithoutResult(tx -> {
            assertThat(fixture().generate(no, "OLD-ACCOUNT")).isEqualTo(1);
            assertThat(fixture().generate(no, "NEW-ACCOUNT")).isZero();
        })).doesNotThrowAnyException();
        assertThat(fixture().count("stl_bill", no)).isEqualTo(1L);
        assertThat(fixture().count("stl_bill_refund_basis", no)).isEqualTo(1L);
    }

    @Test
    void duplicateInputsAreIdempotentWithinGenerationTransaction() {
        fixture().requireBasis();
        String no = fixture().payment();
        var input = fixture().input(no, "OLD-ACCOUNT");
        assertThatCode(() -> assertThat(service.generateForOrder(no, List.of(input, input))).isEqualTo(1))
                .doesNotThrowAnyException();
        assertThat(fixture().count("stl_bill_refund_basis", no)).isEqualTo(1L);
    }

    @Test
    void sameTransactionReplayStillRollsBackBothNewRowsOnOuterAbort() {
        fixture().requireBasis();
        String no = fixture().payment();
        assertThatThrownBy(() -> new TransactionTemplate(payTx).executeWithoutResult(tx -> {
            fixture().generate(no, "OLD-ACCOUNT");
            fixture().generate(no, "NEW-ACCOUNT");
            throw new IllegalStateException("outer abort");
        })).isInstanceOf(IllegalStateException.class).hasMessage("outer abort");
        assertThat(fixture().count("stl_bill", no)).isZero();
        assertThat(fixture().count("stl_bill_refund_basis", no)).isZero();
    }

    @Test
    void cashRefundBeforeFirstBillStillCapturesOriginalFullBasis() {
        fixture().requireBasis();
        String no = fixture().payment();
        jdbc.update("insert into stl_payment(payment_no,direction,order_no,sub_order_no,user_no,pay_channel,"
                + "amount_minor,currency,status,tenant_no,created_at,updated_at,version,deleted)"
                + " values(?,'REFUND',?,?,'BASIS-BUYER','WECHAT',100,'CNY','SUCCESS','MAIN',CURRENT_TIMESTAMP,"
                + "CURRENT_TIMESTAMP,0,0)", "RF-" + no, no, no);
        assertThat(fixture().generate(no, "OLD-ACCOUNT")).isEqualTo(1);
        assertThat(fixture().basis(no)).containsEntry("STATUS", "READY").containsEntry("GOODS_PAID_MINOR", 300L)
                .containsEntry("GROSS_MINOR", 370L).containsEntry("MERCHANT_GOODS_MINOR", 345L);
    }
}
