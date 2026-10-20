package ai.neargo.shop.food.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.ScopeDim;
import java.util.Map;

/** 只注册已落地 Mapper 的真实主体字段。 */
public final class FoodDataScopeRegistration implements DataScopeRegistrar {
    @Override
    public void register(DataScopeTableRegistry registry) {
        registry.register("fnb_table_area", Map.of(ScopeDim.MERCHANT, "entity_no"));
    }
}
