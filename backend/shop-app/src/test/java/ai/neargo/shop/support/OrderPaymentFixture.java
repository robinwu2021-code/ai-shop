package ai.neargo.shop.support;

import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;

/** Persist an actual financial receipt before exercising an order payment projection. */
public final class OrderPaymentFixture {
    private OrderPaymentFixture() {}

    public static String settle(PaymentLedgerService ledger, String order, String user,
            String entity, String channel, long amount) {
        String out = ledger.open(new SettlePort.PaymentOpen(order, user, entity, channel, amount,
                "Scenario checkout"));
        String trade = "TX-" + out;
        ledger.settle(new SettlePort.PaymentSettled(out, channel, trade, amount,
                System.currentTimeMillis()));
        return trade;
    }
}
