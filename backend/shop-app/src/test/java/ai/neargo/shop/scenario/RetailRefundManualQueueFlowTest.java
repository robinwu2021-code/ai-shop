package ai.neargo.shop.scenario;

import ai.neargo.shop.spi.trade.RefundSplitBackPort;
import ai.neargo.shop.pay.service.RefundSplitBackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RetailRefundManualQueueFlowTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RefundSplitBackPort queue;
    @Autowired RefundSplitBackService finance;

    @Test
    void approvedCashRefundWithUnconfirmedReversalIsVisibleWithoutArbitrationLiability() {
        String pending = seed(true);
        String waitingForReturn = seed(false);
        var rows = queue.pendingSplitBacks();
        assertThat(rows.stream().map(RefundSplitBackPort.PendingBack::afterSaleNo))
                .contains(pending).doesNotContain(waitingForReturn);
        var row = rows.stream().filter(r -> r.afterSaleNo().equals(pending)).findFirst().orElseThrow();
        assertThat(row.refundExecution().errorCode()).isEqualTo("REVERSE_UNCONFIRMED");
        assertThat(row.splitReversed()).isFalse();
        var actual = finance.pending().stream().filter(r -> r.afterSaleNo().equals(pending)).findFirst().orElseThrow();
        assertThat(actual.status()).isEqualTo("REFUNDING");
        assertThat(actual.refundExecution().executionNo()).isEqualTo("RF-" + pending);
    }

    private String seed(boolean execution) {
        String no = "RMQ" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,"
                + "refund_minor,reason,created_at,updated_at) VALUES "
                + "(?,?,?,'BUYER','M0001','RETURN_REFUND','REFUNDING',"
                + "1000,'received and approved',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", no, "S-" + no, "O-" + no);
        if (execution) jdbc.update("INSERT INTO ord_refund_execution(execution_no,after_sale_no,sub_order_no,"
                + "order_no,origin_payment_no,amount_minor,currency,status,phase,error_code,resource_status,"
                + "plan_json,stock_needs_json,next_attempt_at,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,1000,'CNY','UNKNOWN','REVERSAL',"
                + "'REVERSE_UNCONFIRMED','PENDING','{}','[]',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "RF-" + no, no, "S-" + no, "O-" + no, "P-" + no);
        return no;
    }
}
