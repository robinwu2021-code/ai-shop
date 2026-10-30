package ai.neargo.shop.merchant.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.ScopeDim;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Generic mapper operations remain subject-scoped; the owner Port additionally enforces store parent chains. */
@Component
public final class PersonnelDataScopeRegistration implements DataScopeRegistrar {
    @Override
    public void register(DataScopeTableRegistry registry) {
        registry.register("mch_resource_occupation", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_resource_occupation_unit", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_resource_occupation_segment", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_resource_occupation_bucket", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_booking_owner", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_resource_command", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_resource_bucket", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_booking_hold", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_booking_hold_unit", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_schedule_rule", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_schedule_change_log", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_resource", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_personnel", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_personnel_account", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_personnel_store", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_personnel_position", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_personnel_skill", Map.of(ScopeDim.MERCHANT, "entity_no"));
    }
}
