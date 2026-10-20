package ai.neargo.shop.beauty.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.ScopeDim;
import java.util.Map;

/** Registers only implemented entities; store authorization remains explicit on root operations. */
public final class BeautyDataScopeRegistration implements DataScopeRegistrar {
    @Override
    public void register(DataScopeTableRegistry registry) {
        registry.register("bty_service_submission", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_trade_binding", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_source_allocation_guard", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_appointment", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_appointment_item", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_appointment_assignment", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_work_order", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_service_task", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_service_step", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_service_participation", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_action_log", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_store_policy", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_store_policy_version", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_service_definition", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_service_definition_version", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_service_requirement", Map.of(ScopeDim.MERCHANT, "entity_no"));
    }
}
