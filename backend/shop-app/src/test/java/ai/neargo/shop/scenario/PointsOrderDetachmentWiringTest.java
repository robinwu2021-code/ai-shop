package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.spi.points.PointsObligationPort;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;
import ai.neargo.shop.trade.order.points.impl.OrderPointsCoordinatorImpl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.UUID;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOrderDetachmentWiringTest.Config.class)
class PointsOrderDetachmentWiringTest {
    @Autowired PointsIntegrationPolicyService policies;
    @Autowired PointsObligationPort obligations;
    @Autowired DetachmentGuard guard;
    @Autowired DataSource data;

    @Test
    void wired_obligation_guard_requires_pay_proof_without_changing_global_participation() {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V452__order_points_integration.sql"),
                        new ClassPathResource("db/migration/V453__order_points_binding.sql"))
                .execute(data);
        String st = "ST_" + UUID.randomUUID().toString().substring(0, 12);
        // Store participation is globally active. The obligation guard remains independently
        // wired for inspection; satisfying it must not revive removed per-store policy writes.
        assertThat(policies.policy(st, "ONLINE").mode()).isEqualTo(Mode.ACTIVE);
        when(obligations.inspect(st)).thenReturn(PointsObligationPort.Obligations.unknown());
        assertThat(guard.canDetach(st)).isFalse();
        when(obligations.inspect(st))
                .thenReturn(new PointsObligationPort.Obligations(true, 0, 0, 0, 0, 0, 0));
        assertThat(guard.canDetach(st)).isTrue();
        assertThatThrownBy(
                        () -> policies.change(
                                new IntegrationPolicyChange(
                                        "X:" + st, st, "ONLINE", Mode.DETACHED,
                                        false, false, 0, "QA"),
                                "OPS"))
                .hasMessage("POINTS_STORE_POLICY_UNSUPPORTED");
        assertThat(policies.policy(st, "ONLINE").mode()).isEqualTo(Mode.ACTIVE);
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
