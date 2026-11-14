package ai.neargo.shop.food.dto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.dto.BottleCustodyContracts.Deposit;
import ai.neargo.shop.food.dto.BottleCustodyContracts.Withdraw;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exact identities and physical quantities cannot be silently coerced across a public custody
 * boundary.
 */
class BottleCustodyWireTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void textualQuantitiesAndNumericCustomerIdentitiesAreRejected() {
        assertThatThrownBy(() -> json.readValue("{\"quantity\":\"1.5\"}", Deposit.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("{\"customerNo\":123}", Deposit.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void unknownRecipientAuthorityAndFractionalVersionsAreRejected() {
        assertThatThrownBy(() -> json.readValue("{\"adminOverride\":true}", Withdraw.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("{\"version\":1.5}", Withdraw.class))
                .isInstanceOf(RuntimeException.class);
    }
}
