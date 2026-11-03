package ai.neargo.commerce.core.subscription;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.*;

import org.junit.jupiter.api.Test;

class StoreProvisioningTest {
    @Test
    void independentEndCannotSpendSubscriptionCredit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SubscriptionService.Delivery(
                        "end-order", SubscriptionService.Origin.ORDER,
                        null, "POS", 30, 100, 1, "CNY", false));
    }

    @Test
    void deliveryAllowsCashOnlyEndsPlanCreditAndZeroValuedSources() {
        assertAll(
                () -> assertDoesNotThrow(() -> new SubscriptionService.Delivery(
                        "cash-end", SubscriptionService.Origin.ORDER,
                        null, "POS", 30, 100, 0, "CNY", false)),
                () -> assertDoesNotThrow(() -> new SubscriptionService.Delivery(
                        "credit-plan", SubscriptionService.Origin.ORDER,
                        "plan-v1", null, 30, 100, 50, "CNY", false)),
                () -> assertDoesNotThrow(() -> new SubscriptionService.Delivery(
                        "gift-end", SubscriptionService.Origin.ORDER,
                        null, "POS", 30, 0, 0, "CNY", true)),
                () -> assertDoesNotThrow(() -> new SubscriptionService.Delivery(
                        "gift-plan", SubscriptionService.Origin.ORDER,
                        "plan-v1", null, 30, 0, 0, "CNY", true)),
                () -> assertDoesNotThrow(() -> new SubscriptionService.Delivery(
                        "grant-end", SubscriptionService.Origin.GRANT,
                        null, "POS", 30, 0, 0, "CNY", false)));
    }

    @Test
    void initializationRejectsSystemAndCrossTenant() {
        var command = new InitializeStoreCommand(new StoreRef("a", "store"), "RETAIL", "op");
        for (var actor :
                java.util.List.of(
                        new ActorContext("a", SubjectKind.SYSTEM, "system", "system"),
                        new ActorContext("b", SubjectKind.ACCOUNT, "owner", "session"))) {
            assertThrows(
                    SecurityException.class,
                    () -> StoreProvisioningService.checkActor(actor, command));
        }
    }
}
