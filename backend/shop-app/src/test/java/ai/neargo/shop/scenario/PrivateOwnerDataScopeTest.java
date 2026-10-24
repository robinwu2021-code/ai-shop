package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeHandler;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.common.data.scope.DataScopeTableRegistry;
import ai.neargo.shop.auth.ScopeDim;
import java.util.List;
import java.util.Set;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Private pricing, printing and source facts cannot become readable through a location-only grant. */
@SpringBootTest
@ActiveProfiles("test")
class PrivateOwnerDataScopeTest {
    private static final List<String> TABLES = List.of(
            "ord_industry_fulfillment", "ord_industry_order_source",
            "ord_manual_price_adjustment", "ord_manual_price_policy", "ord_price_quote",
            "ord_pricing_funding_source", "ord_store_pricing_config", "ord_store_pricing_revision",
            "prd_sku_pricing_policy", "prn_attempt", "prn_device", "prn_job", "prn_route", "prn_template");

    @Autowired private DataScopeTableRegistry registry;
    @Autowired private DataScopeHandler handler;

    @Test
    void locationOnlyOperatorsReceiveAnExplicitDenyForEveryPrivateOwnerTable() {
        var previous = DataScopeContext.current();
        try {
            for (String table : TABLES) {
                assertThat(registry.anchors(table)).as("runtime registration for %s", table)
                        .containsEntry(ScopeDim.MERCHANT, "entity_no")
                        .doesNotContainKeys(ScopeDim.COMMUNITY, ScopeDim.PICKUP);
                for (String dim : List.of(ScopeDim.COMMUNITY, ScopeDim.PICKUP)) {
                    DataScopeContext.set(DataScopeSpec.of(dim, Set.of("LOCATION_ONLY")));
                    var condition = handler.getSqlSegment(new Table(table), null, "privateOwnerProbe");
                    assertThat(condition).as("%s cannot expose %s", dim, table).isNotNull();
                    assertThat(condition.toString().replaceAll("\\s+", "")).isEqualTo("1=0");
                }
                DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("OWNER_ONLY")));
                var merchant = handler.getSqlSegment(new Table(table), null, "privateOwnerProbe");
                assertThat(merchant).as("real owner anchor for %s", table).isNotNull();
                assertThat(merchant.toString()).contains("entity_no", "OWNER_ONLY");
            }
        } finally {
            if (previous == null) DataScopeContext.clear();
            else DataScopeContext.set(previous);
        }
    }
}
