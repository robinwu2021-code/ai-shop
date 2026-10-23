package ai.neargo.shop.pay.channel;

import java.math.BigDecimal;
import java.util.Map;

/** Validates the original identity and exact receipt facts shared by the two WeChat adapters. */
final class PaymentQueryReceipt {
    private PaymentQueryReceipt() {}

    static PayGateway.QueryResult wechat(String originalNumber, Map<String, Object> response) {
        if (!originalNumber.equals(response.get("out_trade_no"))
                || !(response.get("transaction_id") instanceof String trade)
                || trade.isBlank()
                || !(response.get("amount") instanceof Map<?, ?> amount)
                || !(amount.get("currency") instanceof String currency)
                || currency.isBlank()
                || !(amount.get("total") instanceof Number total)) {
            return PayGateway.QueryResult.failed();
        }
        try {
            long minor = new BigDecimal(total.toString()).longValueExact();
            return minor < 0
                    ? PayGateway.QueryResult.failed()
                    : PayGateway.QueryResult.paid(minor, trade, currency);
        } catch (ArithmeticException | NumberFormatException invalidMoney) {
            return PayGateway.QueryResult.failed();
        }
    }
}
