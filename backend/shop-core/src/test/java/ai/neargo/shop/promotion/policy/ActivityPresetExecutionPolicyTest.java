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
    @Test void activeClaimExecutorAdmitsOnlyItsImplementedReceivePreset() {
        assertTrue(policy.missing(actor, "AP004").isEmpty());
        assertTrue(policy.missing(actor, "AP019").contains("PRESET_EXECUTOR"));
    }
    @Test void currentOrderAdapterDoesNotPublishOtherTenantsOrCurrencies() {
        var foreign = new ActivityActor("OTHER", "M1", "U1", null);
        assertTrue(policy.missing(foreign, "AP009").contains("ORDER_TENANT_UNSUPPORTED"));
        var usd = new UnifiedActivityDefinition("优惠", "ORDER", "AP009", 1, null,
                null, null, null, null, null, 1, "USD");
        assertTrue(policy.missing(actor, usd).contains("ORDER_CURRENCY_UNSUPPORTED"));
    }
    @Test void rechargeExecutorsAdmitOnlyTheFiveSameSchemaPresets() {
        for(String code:List.of("AP008","AP024","AP030","AP034","AP037")) {
            assertTrue(policy.missing(actor,code).isEmpty());
            var wrongScene=new UnifiedActivityDefinition("wrong","ORDER",code,1,null,null,null,null,null,null,1,"CNY");
            assertTrue(policy.missing(actor,wrongScene).contains("PRESET_EXECUTOR"));
        }
        assertTrue(policy.missing(actor,"AP031").contains("PRESET_EXECUTOR"));
    }
    @Test void paidProductsAreExecutableRecommendationsButNeverUnifiedActivities() {
        for (String code : List.of("EP001", "EP002", "EP003", "EP004", "EP005", "EP006")) {
            assertTrue(policy.missing(actor, code).isEmpty());
            var invalid = new UnifiedActivityDefinition("card", "ENTITLEMENT_GRANT", code, 1,
                    null, null, null, null, null, null, 1, "CNY");
            assertTrue(policy.missing(actor, invalid).contains("PRESET_EXECUTOR"));
        }
    }
}
