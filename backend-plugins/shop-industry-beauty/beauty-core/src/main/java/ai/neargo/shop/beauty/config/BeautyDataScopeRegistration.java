package ai.neargo.shop.beauty.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.ScopeDim;
import java.util.Map;

/** Registers only implemented entities; store authorization remains explicit on root operations. */
public final class BeautyDataScopeRegistration implements DataScopeRegistrar {
    @Override
    public void register(DataScopeTableRegistry registry) {
        registry.register("bty_action_log", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_store_policy", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_store_policy_version", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_service_definition", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_service_definition_version", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("bty_service_requirement", Map.of(ScopeDim.MERCHANT, "entity_no"));
    }
}
