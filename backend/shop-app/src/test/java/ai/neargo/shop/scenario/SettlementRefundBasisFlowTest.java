package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.SettleService;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 基数属于资金域，真实生成/重放/回滚均通过其独立事务与Mapper。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-basis;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class SettlementRefundBasisFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettleService service;
    @Autowired private ApplicationContext context;
    @Autowired @Qualifier("payTxManager") private PlatformTransactionManager payTx;

    private SettlementBasisFixtures fixture() { return new SettlementBasisFixtures(jdbc, service); }

    @Test
    void backfillForCommittedLegacyBillSurvivesOuterGenerationAbort() {
        fixture().requireBasis();
        String no = fixture().payment();
        fixture().generate(no, "OLD-ACCOUNT");
        jdbc.update("delete from stl_bill_refund_basis where sub_order_no=?", no);
        assertThatThrownBy(() -> new TransactionTemplate(payTx).executeWithoutResult(tx -> {
            fixture().generate(no, "NEW-ACCOUNT");
            throw new IllegalStateException("abort");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(fixture().count("stl_bill_refund_basis", no)).isEqualTo(1L);
    }

    @Test
    void reversalOnlyHistoryCannotBackfillAnUnrefundedOriginal() {
        fixture().requireBasis();
        for (String action : List.of("REVERSE", "SUBSIDY_RETURN")) {
            String no = fixture().payment();
            fixture().generate(no, "OLD-ACCOUNT");
            jdbc.update("delete from stl_bill_refund_basis where sub_order_no=?", no);
            jdbc.update("insert into stl_split_log(settle_no,sub_order_no,split_action,amount_minor,request_no,"
                    + "result,at,tenant_no,created_at) values(?,?,?,100,?,'SUCCESS',1,'MAIN',CURRENT_TIMESTAMP)",
                    fixture().bill(no).get("SETTLE_NO"), no, action, "REV-" + no);
            fixture().generate(no, "NEW-ACCOUNT");
            assertThat(fixture().basis(no)).containsEntry("STATUS", "NEEDS_REVIEW")
                    .containsEntry("GROSS_MINOR", null).containsEntry("MERCHANT_GOODS_MINOR", null);
        }
    }

    @Test
    void subsidyReversalAndPendingCashHistoryRequireReview() {
        fixture().requireBasis();
        for (String direction : List.of("SUBSIDY_REVERSE", "REFUND")) {
            String no = fixture().payment();
            fixture().generate(no, "OLD-ACCOUNT");
            jdbc.update("delete from stl_bill_refund_basis where sub_order_no=?", no);
            jdbc.update("insert into stl_payment(payment_no,direction,order_no,sub_order_no,user_no,pay_channel,"
                    + "amount_minor,currency,status,tenant_no,created_at,updated_at,version,deleted)"
                    + " values(?,?,?,?,'BASIS-BUYER','WECHAT',100,'CNY','PENDING','MAIN',CURRENT_TIMESTAMP,"
                    + "CURRENT_TIMESTAMP,0,0)", "RF-" + no, direction, no, no);
            fixture().generate(no, "NEW-ACCOUNT");
            assertThat(fixture().basis(no)).containsEntry("STATUS", "NEEDS_REVIEW").containsEntry("GROSS_MINOR", null);
        }
    }

    @Test
    void incompleteOrMismatchedHistoricalAccountNeverBecomesReady() {
        fixture().requireBasis();
        String[][] missing = {{"wx_appid", "stl_payment", null}, {"pay_scene", "stl_bill", null},
                {"pay_scene", "stl_bill", "IOS"}, {"fee_bearer", "stl_bill", "UNKNOWN"}};
        for (String[] fact : missing) {
            String no = fixture().payment();
            fixture().generate(no, "OLD-ACCOUNT");
            jdbc.update("delete from stl_bill_refund_basis where sub_order_no=?", no);
            jdbc.update("update " + fact[1] + " set " + fact[0] + "=? where "
                    + ("stl_bill".equals(fact[1]) ? "sub_order_no" : "order_no") + "=?", fact[2], no);
            fixture().generate(no, "NEW-ACCOUNT");
            assertThat(fixture().basis(no)).containsEntry("STATUS", "NEEDS_REVIEW");
            assertThat(fixture().basis(no).get("REVIEW_REASON")).isNotNull();
        }
    }

    @Test
    void basisSupportsExisting64CharacterIdentifiersAndRetainedAccountVersion() {
        fixture().requireBasis();
        String no = "L".repeat(60);
        fixture().payment(no);
        assertThatCode(() -> fixture().generate(no, "A".repeat(64))).doesNotThrowAnyException();
        jdbc.update("delete from stl_bill_refund_basis where sub_order_no=?", no);
        jdbc.update("update stl_bill set settle_no=? where sub_order_no=?", "S".repeat(64), no);
        assertThatCode(() -> fixture().generate(no, "NEW-ACCOUNT")).doesNotThrowAnyException();
        assertThat(fixture().basis(no)).containsEntry("ORIGIN_PAYMENT_NO", "PAY-" + no)
                .containsEntry("ACCOUNT_SNAPSHOT_REF", "S".repeat(64) + "#1")
                .containsEntry("PAY_MERCHANT_NO", "A".repeat(64));
    }

    @Test
    void generatedBasisPreservesEveryOriginalAmountAndAccountAcrossReplay() {
        fixture().requireBasis();
        String no = fixture().payment();
        assertThat(fixture().generate(no, "OLD-ACCOUNT")).isEqualTo(1);
        assertThat(fixture().count("stl_bill_refund_basis", no)).isEqualTo(1L);
        Map<String, Object> basis = fixture().basis(no);
        Map<String, Object> bill = fixture().bill(no);
        assertThat(basis).containsEntry("STATUS", "READY").containsEntry("SOURCE", "ORIGINAL")
                .containsEntry("GOODS_PAID_MINOR", 300L).containsEntry("GROSS_MINOR", 370L)
                .containsEntry("FREIGHT_INCOME_MINOR", 50L).containsEntry("FREIGHT_COST_MINOR", 0L)
                .containsEntry("POINTS_FEE_MINOR", 7L).containsEntry("POINTS_COST_MINOR", 0L)
                .containsEntry("SUBSIDY_MINOR", 20L).containsEntry("PAYMENT_ROUTE", "DIRECT")
                .containsEntry("ORIGIN_PAYMENT_NO", "PAY-" + no).containsEntry("PAY_MERCHANT_NO", "OLD-ACCOUNT")
                .containsEntry("CURRENCY", "CNY").containsEntry("PAY_CHANNEL", "WECHAT")
                .containsEntry("PAY_SCENE", "MP_WECHAT").containsEntry("WX_APPID", "ORIGINAL-APP")
                .containsEntry("FEE_BEARER", "MERCHANT").containsEntry("BASIS_VERSION", 1);
        assertThat(basis.get("ACCOUNT_SNAPSHOT_REF")).isEqualTo(bill.get("SETTLE_NO") + "#1");
        assertThat(basis.get("MERCHANT_GOODS_MINOR"))
                .isEqualTo(((Number) bill.get("NET_MINOR")).longValue() - 50L);
        for (String field : List.of("COMMISSION_MINOR", "SERVICE_FEE_MINOR")) {
            assertThat(basis.get(field)).isEqualTo(bill.get(field));
        }
        jdbc.update("update stl_bill set gross_minor=10,net_minor=5,commission_minor=3,"
                + "refunded_goods_minor=100,pay_merchant_no='NEW-ACCOUNT' where sub_order_no=?", no);
        assertThat(fixture().generate(no, "NEW-ACCOUNT")).isZero();
        assertThat(fixture().basis(no)).isEqualTo(basis);
    }

    @Test
    void unrefundedLegacyBillBackfillsWithoutRecalculatingCurrentRates() {
        fixture().requireBasis();
        String no = fixture().payment();
        fixture().generate(no, "OLD-ACCOUNT");
        Map<String, Object> original = fixture().basis(no);
        jdbc.update("delete from stl_bill_refund_basis where sub_order_no=?", no);
        assertThat(fixture().generate(no, "NEW-ACCOUNT")).isZero();
        Map<String, Object> restored = fixture().basis(no);
        assertThat(restored).containsEntry("SOURCE", "LEGACY_UNREFUNDED");
        for (String field : List.of("GROSS_MINOR", "COMMISSION_MINOR", "MERCHANT_GOODS_MINOR", "PAY_MERCHANT_NO")) {
            assertThat(restored.get(field)).isEqualTo(original.get(field));
        }
    }

    @Test
    void historicalRefundCannotUseShrunkenBillAsOriginalBasis() {
        fixture().requireBasis();
        String no = fixture().payment();
        fixture().generate(no, "OLD-ACCOUNT");
        jdbc.update("delete from stl_bill_refund_basis where sub_order_no=?", no);
        jdbc.update("update stl_bill set net_minor=5,gross_minor=10,refunded_goods_minor=100 where sub_order_no=?", no);
        fixture().generate(no, "NEW-ACCOUNT");
        assertThat(fixture().basis(no)).containsEntry("STATUS", "NEEDS_REVIEW")
                .containsEntry("REVIEW_REASON", "HISTORICAL_REFUNDS")
                .containsEntry("GOODS_PAID_MINOR", null).containsEntry("MERCHANT_GOODS_MINOR", null);
    }

    @Test
    void cashRefundTraceAlsoBlocksLegacyBackfillWithoutBillCuts() {
        fixture().requireBasis();
        String no = fixture().payment();
        fixture().generate(no, "OLD-ACCOUNT");
        jdbc.update("delete from stl_bill_refund_basis where sub_order_no=?", no);
        jdbc.update("insert into stl_payment(payment_no,direction,order_no,sub_order_no,user_no,pay_channel,"
                + "amount_minor,currency,status,tenant_no,created_at,updated_at,version,deleted)"
                + " values(?,'REFUND',?,?,'BASIS-BUYER','WECHAT',100,'CNY','SUCCESS','MAIN',CURRENT_TIMESTAMP,"
                + "CURRENT_TIMESTAMP,0,0)", "RF-" + no, no, no);
        fixture().generate(no, "NEW-ACCOUNT");
        assertThat(fixture().basis(no)).containsEntry("STATUS", "NEEDS_REVIEW").containsEntry("GROSS_MINOR", null);
    }

    @Test
    void basisAndBillCommitTogetherUnderPayTransaction() {
        fixture().requireBasis();
        String no = fixture().payment();
        assertThatThrownBy(() -> new TransactionTemplate(payTx).executeWithoutResult(tx -> {
            fixture().generate(no, "OLD-ACCOUNT");
            throw new IllegalStateException("abort");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(fixture().count("stl_bill", no)).isZero();
        assertThat(fixture().count("stl_bill_refund_basis", no)).isZero();
        assertThat(context.containsBean("refundBasisMapper")).isTrue();
        var factory = (MapperFactoryBean<?>) context.getBean("&refundBasisMapper");
        assertThat(factory.getSqlSessionFactory()).isSameAs(context.getBean("paySqlSessionFactory"));
    }

}
