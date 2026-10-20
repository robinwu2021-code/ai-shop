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
import org.springframework.jdbc.core.JdbcTemplate;
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
                        new ClassPathResource("db/migration/V452__order_points_integration.sql"),
                        new ClassPathResource("db/migration/V453__order_points_binding.sql"))
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

    String store() {
        return "ST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Compatibility helper: store participation is global and needs no per-store activation. */
    String activate() {
        return store();
    }

    @Test
    void admits_new_binding_even_when_legacy_store_policy_is_detached() {
        String store = "ST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        new JdbcTemplate(data)
                .update(
                        "INSERT INTO ord_points_integration_policy(store_no,scene,mode,version)"
                                + " VALUES(?,?,?,?)",
                        store,
                        "ONLINE",
                        "DETACHED",
                        0);

        var binding = bindings.admit(admission(store, "S:" + store));

        assertThat(binding.phase()).isEqualTo(Phase.PENDING);
        assertThat(policies.policy(store, "ONLINE").mode()).isEqualTo(Mode.ACTIVE);
    }

    @Test
    void admits_all_stores_and_replays_binding_without_store_policy_rows() {
        String store = store();
        var c = admission(store, "S:" + store);
        var first = bindings.admit(c);
        assertThat(bindings.admit(c)).isEqualTo(first);
        assertThat(bindings.find(c.subOrderNo()).snapshot().quotedMinor()).isEqualTo(10);
        assertThat(bindings.admit(admission(store(), "NEW:" + store))).isNotNull();
        assertThat(policies.policy(store, "ONLINE").mode()).isEqualTo(Mode.ACTIVE);
    }

    @Test
    void rejects_changed_body_cross_store_and_stale_policy_without_partial_binding() {
        String store = store();
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
        String store = store();
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
