package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService.*;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.*;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOrderBindingTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsOrderBindingTest {
    @Autowired OrderPointsBindingService bindings;
    @Autowired PointsIntegrationPolicyService policies;
    @Autowired DataSource data;

    @BeforeAll
    void schema() {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V427__order_points_integration.sql"),
                        new ClassPathResource("db/migration/V428__order_points_binding.sql"))
                .execute(data);
    }

    Admission admission(String store, String sub) {
        var route =
                new PointsRefundPort.PaymentSnapshot(
                        "DIRECT", "TEST", "M", store, "V1", "SM", null, "OUT:" + sub, 100);
        var snapshot =
                new Snapshot(
                        100,
                        20,
                        10,
                        2,
                        1,
                        "RULE_V1",
                        "AGREEMENT_V1",
                        route,
                        List.of(new PointsRedeemPort.Item("ITEM", 100)));
        return new Admission(
                "REQ:" + sub,
                "O:" + sub,
                sub,
                "U",
                "M",
                store,
                "TYPE",
                "CNY",
                "ONLINE",
                0,
                true,
                true,
                snapshot);
    }

    String activate() {
        String store = "ST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        policies.change(
                new IntegrationPolicyChange(
                        "ACTIVE:" + store, store, "ONLINE", Mode.ACTIVE, true, true, -1, "QA"),
                "OPS");
        return store;
    }

    @Test
    void freezes_original_binding_and_replays_even_after_stopping_new_admission() {
        String store = activate();
        var c = admission(store, "S:" + store);
        var first = bindings.admit(c);
        policies.change(
                new IntegrationPolicyChange(
                        "DRAIN:" + store, store, "ONLINE", Mode.DRAINING, false, false, 0, "QA"),
                "OPS");
        assertThat(bindings.admit(c)).isEqualTo(first);
        assertThat(bindings.find(c.subOrderNo()).snapshot().quotedMinor()).isEqualTo(10);
        assertThatThrownBy(() -> bindings.admit(admission(store, "NEW:" + store)))
                .hasMessageContaining("ADMISSION_CLOSED");
    }

    @Test
    void rejects_changed_body_cross_store_and_stale_policy_without_partial_binding() {
        String store = activate();
        var c = admission(store, "S:" + store);
        bindings.admit(c);
        var changed =
                new Admission(
                        c.requestId(),
                        c.orderNo(),
                        c.subOrderNo(),
                        "OTHER",
                        c.merchantNo(),
                        store,
                        c.typeCode(),
                        c.currency(),
                        c.scene(),
                        0,
                        true,
                        true,
                        c.snapshot());
        assertThatThrownBy(() -> bindings.admit(changed)).hasMessageContaining("CONFLICT");
        var wrongStore =
                new Admission(
                        "REQ:BAD:" + store,
                        "O",
                        "BAD:" + store,
                        "U",
                        "M",
                        store,
                        "TYPE",
                        "CNY",
                        "ONLINE",
                        0,
                        true,
                        true,
                        admission("ST_OTHER", "OTHER").snapshot());
        assertThatThrownBy(() -> bindings.admit(wrongStore)).hasMessageContaining("STORE");
        assertThat(bindings.find("BAD:" + store)).isNull();
        var stale =
                new Admission(
                        "REQ:STALE:" + store,
                        "O",
                        "STALE:" + store,
                        "U",
                        "M",
                        store,
                        "TYPE",
                        "CNY",
                        "ONLINE",
                        99,
                        true,
                        true,
                        c.snapshot());
        assertThatThrownBy(() -> bindings.admit(stale)).hasMessageContaining("REVISION");
        assertThat(bindings.find(stale.subOrderNo())).isNull();
    }

    @Test
    void phase_cas_preserves_original_hold_number() {
        String store = activate();
        var b = bindings.admit(admission(store, "S:" + store));
        var change = new Transition(b.bindingNo(), 0, Phase.BOUND, "HOLD");
        var bound = bindings.transition(change);
        assertThat(bindings.transition(change)).isEqualTo(bound);
        assertThatThrownBy(
                        () ->
                                bindings.transition(
                                        new Transition(b.bindingNo(), 0, Phase.CLOSING, "HOLD")))
                .hasMessageContaining("REVISION");
        assertThatThrownBy(
                        () ->
                                bindings.transition(
                                        new Transition(
                                                b.bindingNo(), 1, Phase.CLOSING, "OTHER_HOLD")))
                .hasMessageContaining("HOLD");
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @Import({
        PointsOrderSourceTestConfiguration.class,
        ai.neargo.shop.trade.order.points.impl.OrderPointsBindingServiceImpl.class
    })
    static class Config {}
}
