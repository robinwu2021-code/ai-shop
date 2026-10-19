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
    @Autowired DataSource data;

    @Test
    void wired_guard_keeps_draining_until_pay_proves_clear() {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V452__order_points_integration.sql"),
                        new ClassPathResource("db/migration/V453__order_points_binding.sql"))
                .execute(data);
        String st = "ST_" + UUID.randomUUID().toString().substring(0, 12);
        policies.change(
                new IntegrationPolicyChange(
                        "A:" + st, st, "ONLINE", Mode.ACTIVE, true, false, -1, "QA"),
                "OPS");
        policies.change(
                new IntegrationPolicyChange(
                        "D:" + st, st, "ONLINE", Mode.DRAINING, false, false, 0, "QA"),
                "OPS");
        when(obligations.inspect(st)).thenReturn(PointsObligationPort.Obligations.unknown());
        var detach =
                new IntegrationPolicyChange(
                        "X:" + st, st, "ONLINE", Mode.DETACHED, false, false, 1, "QA");
        assertThatThrownBy(() -> policies.change(detach, "OPS"))
                .hasMessageContaining("OBLIGATIONS_OPEN");
        assertThat(policies.policy(st, "ONLINE").mode()).isEqualTo(Mode.DRAINING);
        when(obligations.inspect(st))
                .thenReturn(new PointsObligationPort.Obligations(true, 0, 0, 0, 0, 0, 0));
        assertThat(policies.change(detach, "OPS").mode()).isEqualTo(Mode.DETACHED);
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
