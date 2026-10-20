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
        registry.register("mch_personnel", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_personnel_account", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_personnel_store", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_personnel_position", Map.of(ScopeDim.MERCHANT, "entity_no"));
        registry.register("mch_personnel_skill", Map.of(ScopeDim.MERCHANT, "entity_no"));
    }
}
