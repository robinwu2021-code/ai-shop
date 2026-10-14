package ai.neargo.shop.support;

import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Adds an actual in-memory TEST-bank counterpart to old order fixtures that only sent stateless stub callbacks. */
public final class RefundScenarioPayments {
    private RefundScenarioPayments() { }

    public static void received(JdbcTemplate jdbc, PaymentLedgerService ledger, TestPayGateway channel, String as) {
        var source = jdbc.queryForMap("SELECT o.order_no,o.user_no,o.pay_amount FROM ord_order o"
                + " JOIN ord_after_sale a ON a.order_no=o.order_no WHERE a.after_sale_no=?", as);
        String order = (String) source.get("order_no");
        var existing = jdbc.queryForList("SELECT out_trade_no FROM stl_payment WHERE order_no=?"
                + " AND direction='PAY' AND status='SUCCESS' AND pay_channel='TEST' ORDER BY id DESC",
                String.class, order);
        if (!existing.isEmpty()) {
            // These fixtures reported success through the stateless stub; mirror the receipt in the TEST bank too.
            channel.markPaid(existing.getFirst());
            return;
        }
        var unfinished = jdbc.queryForList("SELECT out_trade_no FROM stl_payment WHERE order_no=?"
                + " AND direction='PAY' AND status IN ('INIT','PENDING')", String.class, order);
        for (String out : unfinished) { ledger.close(out, "Replace stateless stub fixture with TEST-bank payment"); }
        long amount = ((Number) source.get("pay_amount")).longValue();
        String out = ledger.open(new SettlePort.PaymentOpen(order, (String) source.get("user_no"),
                null, "TEST", amount, "Refund scenario fixture"));
        String trade = channel.placeOrder(out, amount);
        channel.markPaid(out);
        ledger.settle(new SettlePort.PaymentSettled(out, "TEST", trade, amount, System.currentTimeMillis()));
        jdbc.update("UPDATE ord_order SET pay_channel='TEST' WHERE order_no=?", order);
        jdbc.update("UPDATE stl_bill SET pay_channel='TEST' WHERE order_no=?", order);
    }
}
