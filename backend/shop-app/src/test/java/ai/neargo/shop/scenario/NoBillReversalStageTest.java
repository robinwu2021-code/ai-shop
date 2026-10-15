package ai.neargo.shop.scenario;

import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import ai.neargo.shop.spi.settle.SettlePort.ReversalStage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NoBillReversalStageTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlePort settle;

    @Test
    void missingBillWithPriorSplitIntentMustNotBeSuccessfulReversal() {
        String no = "NO-BILL-INTENT";
        order(no, "WAIT_PAY");
        jdbc.update("insert into stl_split_log(settle_no,sub_order_no,split_action,amount_minor,request_no,result,"
                + "at,tenant_no,created_at) values(?,?,'SPLIT',270,?,'SUCCESS',1,'MAIN',CURRENT_TIMESTAMP)",
                "STL-" + no, no, "REQ-" + no);
        assertThat(settle.reverseSplit(new SplitReversal(no, "AS-" + no, 300, 0, 300))).isFalse();
    }
    private void order(String no, String status) {
        jdbc.update("insert into ord_order(order_no,user_no,status,tenant_no,created_at,updated_at,version,deleted)"
                + " values(?,'STAGE-BUYER',?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)", "P-" + no, status);
        jdbc.update("insert into ord_sub_order(sub_order_no,order_no,user_no,entity_no,status,tenant_no,"
                + "created_at,updated_at,version,deleted) values(?,?,'STAGE-BUYER','M0001',?,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)", no, "P-" + no, status);
    }

    @Test
    void knownUnpaidWithoutAnyIntentReturnsNotRequiredWithoutInventedJournal() {
        String no = "NO-BILL-UNPAID";
        order(no, "WAIT_PAY");
        var stage = settle.reverseStage(new SplitReversal(no, "AS-" + no, 0, 0, 0));
        assertThat(stage.stage()).isEqualTo(ReversalStage.NOT_REQUIRED);
        assertThat(stage.permitsCashRefund()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from stl_bill_refund where sub_order_no=?", Long.class, no))
                .isZero();
    }

    @Test
    void paidOrderWithMissingExpectedBillRequiresReview() {
        String no = "NO-BILL-PAID";
        order(no, "PAID");
        var stage = settle.reverseStage(new SplitReversal(no, "AS-" + no, 300, 0, 300));
        assertThat(stage.stage()).isEqualTo(ReversalStage.NEEDS_REVIEW);
        assertThat(stage.permitsCashRefund()).isFalse();
    }

    @Test
    void unknownOrderIsNotUnpaidEvidence() {
        assertThat(settle.reverseStage(new SplitReversal("NO-SUCH-SUB", "AS-UNKNOWN", 0, 0, 0)).stage())
                .isEqualTo(ReversalStage.NEEDS_REVIEW);
    }

    @Test
    void cancelledOrderWithLateCashButNoSupplierIntentNeedsNoReversal() {
        String no = "NO-BILL-CANCELLED";
        order(no, "CANCELLED");
        new SettlementBasisFixtures(jdbc, null).payment("P-" + no);
        assertThat(settle.reverseStage(new SplitReversal(no, "AS-" + no, 300, 0, 300)).stage())
                .isEqualTo(ReversalStage.NOT_REQUIRED);
    }

}
