package ai.neargo.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;

/** A real database failure after the financial claim commits models interruption before local zero completion. */
public final class ZeroRefundCrashFixture {
    private final JdbcTemplate jdbc;
    private final BasicRefundExecutionService refunds;

    public ZeroRefundCrashFixture(WebApplicationContext context) {
        jdbc = context.getBean(JdbcTemplate.class);
        refunds = context.getBean(BasicRefundExecutionService.class);
    }

    public void approveWithInterruptedCompletion(BasicRefundFixture fixture, BasicRefundFixture.Purchase purchase,
                                                String afterSale) {
        assertThat(afterSale).matches("[A-Za-z0-9-]+");
        String constraint = "ck_zero_crash_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.execute("alter table stl_payment add constraint " + constraint + " check (after_sale_no <> '"
                + afterSale + "' or direction <> 'REFUND' or amount_minor <> 0 or status <> 'SUCCESS')");
        try {
            fixture.approve(purchase, afterSale);
        } finally {
            jdbc.execute("alter table stl_payment drop constraint " + constraint);
        }
        assertThat(jdbc.queryForObject("select status from stl_payment where order_no=? and direction='PAY'",
                String.class, purchase.payNo())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("select count(*) from ord_refund_execution e join stl_payment r"
                + " on r.refund_execution_no=e.execution_no join stl_payment p on p.payment_no=e.origin_payment_no"
                + " where e.after_sale_no=? and e.sub_order_no=? and e.order_no=? and e.amount_minor=0"
                + " and r.amount_minor=0 and r.status='PENDING' and r.succeeded_at is null"
                + " and r.origin_payment_no=p.payment_no and r.after_sale_no=e.after_sale_no"
                + " and r.sub_order_no=e.sub_order_no and r.user_no=p.user_no and r.pay_channel=p.pay_channel"
                + " and r.currency=e.currency and p.currency=e.currency and p.direction='PAY' and p.status='SUCCESS'",
                Integer.class, afterSale, purchase.subNo(), purchase.payNo())).isOne();
        assertThat(jdbc.queryForObject("select status from ord_refund_execution where after_sale_no=?",
                String.class, afterSale)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",
                String.class, afterSale)).isEqualTo("REFUNDING");
    }

    public void recoverAndAssert(String afterSale, long quantity) {
        refunds.recover("RF-" + afterSale);
        refunds.recover("RF-" + afterSale);
        assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",
                String.class, afterSale)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select count(*) from stl_payment where after_sale_no=?"
                + " and direction='REFUND' and status='SUCCESS' and amount_minor=0", Long.class, afterSale)).isOne();
        assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                Long.class, "RF-" + afterSale)).isEqualTo(quantity);
        assertThat(jdbc.queryForObject("select resource_status from ord_refund_execution where after_sale_no=?",
                String.class, afterSale)).isEqualTo("DONE");
    }
}
