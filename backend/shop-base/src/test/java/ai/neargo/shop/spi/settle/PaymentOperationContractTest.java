package ai.neargo.shop.spi.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.OfflineReceiptPort.OfflineReceiptReq;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Public financial inputs cannot coerce money, inject authority or leak reusable parameters in
 * diagnostics.
 */
class PaymentOperationContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void receiptJsonRequiresAnExactLongAndRejectsCallerAuthority() {
        String body =
                "{\"operation\":{\"caller\":\"BEAUTY\",\"operationNo\":\"O\"},"
                    + "\"requestKey\":\"secret-key\",\"method\":\"CASH\",\"amountMinor\":%s,"
                        + "\"currency\":\"CNY\",\"receivedAt\":\"2026-10-23T00:00:00Z\",\"note\":\"actual"
                    + " receipt\"}";
        for (String amount :
                new String[] {"\"303\"", "303.0", "null", "-1", "9223372036854775808"}) {
            assertThatThrownBy(
                            () -> json.readValue(body.formatted(amount), OfflineReceiptReq.class))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(
                        json.readValue(
                                        body.formatted("9223372036854775807"),
                                        OfflineReceiptReq.class)
                                .amountMinor())
                .isEqualTo(Long.MAX_VALUE);
        String authority =
                body.formatted("303")
                        .replace("\"method\":", "\"actorAccountNo\":\"forged\",\"method\":");
        assertThatThrownBy(() -> json.readValue(authority, OfflineReceiptReq.class))
                .isInstanceOf(RuntimeException.class);
        String missing = body.formatted("303").replace("\"amountMinor\":303,", "");
        assertThatThrownBy(() -> json.readValue(missing, OfflineReceiptReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThat(json.readValue(body.formatted("303"), OfflineReceiptReq.class).toString())
                .doesNotContain("secret-key", "actual receipt");
    }

    @Test
    void requestLookupNeverAmbiguouslyCombinesIdentityWithAKeyAndViewsFreezePrivateParameters() {
        var operation = new CheckoutPort.OperationRef("BEAUTY", "O");
        assertThatThrownBy(() -> new CheckoutPort.PaymentLookup(operation, "PO", "private-key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckoutPort.PaymentLookup(operation, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CheckoutPort.PaymentLookup(operation, null, "private-key").toString())
                .doesNotContain("private-key");
        var parameters = new HashMap<>(Map.of("paySign", "reusable-secret"));
        var view =
                new CheckoutPort.PaymentAttemptView(
                        operation,
                        "PO",
                        "PAYABLE",
                        "PAY",
                        "OUT",
                        "TEST",
                        "CNY",
                        303,
                        parameters,
                        null,
                        null,
                        Instant.now());
        parameters.clear();
        assertThat(view.payParams()).containsEntry("paySign", "reusable-secret");
        assertThatThrownBy(() -> view.payParams().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(view.toString()).doesNotContain("reusable-secret");
    }
}
