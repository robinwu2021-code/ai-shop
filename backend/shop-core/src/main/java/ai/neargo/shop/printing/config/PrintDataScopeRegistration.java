package ai.neargo.shop.printing.config;

import ai.neargo.common.data.scope.DataScopeRegistrar;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.ScopeDim;
import java.util.Map;

/** Registers actual shared print ownership columns with the original platform data-scope defense. */
public final class PrintDataScopeRegistration implements DataScopeRegistrar {
    @Override public void register(DataScopeTableRegistry registry) {
        for(String table:new String[]{"prn_device","prn_template","prn_route","prn_job","prn_attempt","prn_incident","prn_operation","prn_printer","prn_request","prn_template_catalog","prn_route_catalog"}) {
            registry.register(table,Map.of(ScopeDim.MERCHANT,"entity_no"));
        }
    }
}
