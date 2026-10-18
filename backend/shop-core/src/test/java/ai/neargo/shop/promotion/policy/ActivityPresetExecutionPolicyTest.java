package ai.neargo.shop.promotion.policy;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActivityPresetExecutionPolicyTest {
    private final ActivityPresetExecutionPolicy policy = new ActivityPresetExecutionPolicy();
    private final ActivityActor actor = new ActivityActor("MAIN", "M1", "U1", null);
    @Test void genericCutReadinessDoesNotProveNewCustomerOrFirstOrderExecution() {
        assertEquals(List.of("PRESET_EXECUTOR"), policy.missing(actor, "AP001"));
        assertTrue(policy.missing(actor, "AP009").isEmpty());
        assertTrue(policy.missing(actor, "AP010").isEmpty());
        assertEquals(List.of("PRESET_EXECUTOR"), policy.missing(actor, "AP014"));
    }
    @Test void currentOrderAdapterDoesNotPublishOtherTenantsOrCurrencies() {
        var foreign = new ActivityActor("OTHER", "M1", "U1", null);
        assertTrue(policy.missing(foreign, "AP009").contains("ORDER_TENANT_UNSUPPORTED"));
        var usd = new UnifiedActivityDefinition("优惠", "ORDER", "AP009", 1, null,
                null, null, null, null, null, 1, "USD");
        assertTrue(policy.missing(actor, usd).contains("ORDER_CURRENCY_UNSUPPORTED"));
    }
}
