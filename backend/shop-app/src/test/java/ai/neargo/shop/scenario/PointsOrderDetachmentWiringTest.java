package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.points.PointsObligationPort;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.DetachmentGuard;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.IntegrationPolicyChange;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.Mode;
import ai.neargo.shop.trade.order.points.impl.OrderPointsCoordinatorImpl;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(PointsOrderDetachmentWiringTest.Config.class)
class PointsOrderDetachmentWiringTest {
    @Autowired PointsIntegrationPolicyService policies;
    @Autowired DetachmentGuard coordinator;
    @Autowired PointsObligationPort obligations;
    @Autowired DataSource data;

    @Test
    void wired_guard_requires_pay_to_prove_all_obligations_clear() {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V452__order_points_integration.sql"),
                        new ClassPathResource("db/migration/V453__order_points_binding.sql"))
                .execute(data);
        String store = "ST_" + UUID.randomUUID().toString().substring(0, 12);
        when(obligations.inspect(store)).thenReturn(PointsObligationPort.Obligations.unknown());
        assertThat(coordinator.canDetach(store)).isFalse();
        when(obligations.inspect(store))
                .thenReturn(new PointsObligationPort.Obligations(true, 0, 0, 0, 0, 0, 1));
        assertThat(coordinator.canDetach(store)).isFalse();
        when(obligations.inspect(store))
                .thenReturn(new PointsObligationPort.Obligations(true, 0, 0, 0, 0, 0, 0));
        assertThat(coordinator.canDetach(store)).isTrue();
    }

    @Test
    void all_stores_participate_and_store_policy_mutations_remain_unsupported() {
        String store = "ST_" + UUID.randomUUID().toString().substring(0, 12);
        var original = policies.policy(store, "ONLINE");
        assertThat(original.mode()).isEqualTo(Mode.ACTIVE);
        assertThat(original.earnEnabled()).isTrue();
        assertThat(original.redeemEnabled()).isTrue();
        for (Mode mode : Mode.values()) {
            var change = new IntegrationPolicyChange(
                    UUID.randomUUID().toString(), store, "ONLINE", mode, false, false, 0, "QA");
            assertThatThrownBy(() -> policies.change(change, "OPS"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("POINTS_STORE_POLICY_UNSUPPORTED");
        }
        assertThat(policies.policy(store, "ONLINE")).isEqualTo(original);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @Import({PointsOrderBindingTest.Config.class, OrderPointsCoordinatorImpl.class})
    static class Config {
        @Bean
        PointsObligationPort obligations() {
            return mock(PointsObligationPort.class);
        }
    }
}
