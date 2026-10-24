package ai.neargo.shop.trade.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.ScopeDim;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Store-validated original-source services explicitly bypass only after checking trusted ownership. */
@Component
public class IndustryFulfillmentDataScopeRegistration implements DataScopeRegistrar {
    @Override public void register(DataScopeTableRegistry registry) {
        registry.register("ord_industry_fulfillment",Map.of(ScopeDim.MERCHANT,"entity_no"));
    }
}
