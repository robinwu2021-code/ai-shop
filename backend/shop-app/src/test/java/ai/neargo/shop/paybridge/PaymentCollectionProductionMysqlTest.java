package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;

/** Store routing by near-pay's signed store number (TDD-沙箱收款消息与SSE, 2026-11-16 increment, AC3–AC5). */
@EnabledIfEnvironmentVariable(named = "PAYMENT_SSE_JDBC_URL", matches = "jdbc:mysql://127.*")
class PaymentCollectionProductionMysqlTest {

    @Test
    @DisplayName("AC3: without a binding, a signed PRODUCTION result is routed to the store near-pay resolved")
    void unboundProductionResultUsesSignedStore() throws Exception {
        try (var f = new PaymentCollectionMysqlFixture()) {
            var fact = f.fact(201, 7777, "ST_SSE_OTHER");
            var accepted = f.service.receive("PRODUCTION", fact, f.json.writeValueAsString(fact));
            assertThat(accepted.status()).isEqualTo("APPLIED");
            assertThat(f.jdbc.queryForObject("SELECT store_no FROM payment_collection_receipt WHERE receipt_no=?",
                    String.class, accepted.sourceReceiptNo())).isEqualTo("ST_SSE_OTHER");
            assertThat(f.jdbc.queryForObject("SELECT environment FROM payment_collection_receipt WHERE receipt_no=?",
                    String.class, accepted.sourceReceiptNo())).isEqualTo("PRODUCTION");
        }
    }

    @Test
    @DisplayName("AC3: an unknown store number is refused")
    void unknownStoreIsRefused() throws Exception {
        try (var f = new PaymentCollectionMysqlFixture()) {
            var fact = f.fact(202, 7777, "ST_DOES_NOT_EXIST");
            assertThatThrownBy(() -> f.service.receive("PRODUCTION", fact, f.json.writeValueAsString(fact)))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    @DisplayName("AC4: an explicit binding that disagrees with the signed store number is refused")
    void bindingMustAgreeWithSignedStore() throws Exception {
        try (var f = new PaymentCollectionMysqlFixture()) {
            // The fixture binds SANDBOX/42/CHECKOUT/42/9001 to ST_SSE_SANDBOX.
            var fact = f.fact(203, 9001, "ST_SSE_OTHER");
            assertThatThrownBy(() -> f.service.receive("SANDBOX", fact, f.json.writeValueAsString(fact)))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    @DisplayName("AC5: no store number and no binding stays refused, as before")
    void noStoreNoBindingIsRefused() throws Exception {
        try (var f = new PaymentCollectionMysqlFixture()) {
            var fact = f.fact(204, 7777, null);
            assertThatThrownBy(() -> f.service.receive("PRODUCTION", fact, f.json.writeValueAsString(fact)))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }
}
