package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOrderSourceTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsOrderIntegrationPolicyTest {
    @Autowired PointsIntegrationPolicyService policy;
    @Autowired DataSource data;

    @BeforeAll
    void schema() {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V452__order_points_integration.sql"))
                .execute(data);
    }

    @Test
    void global_default_enables_all_stores_and_ignores_legacy_store_policy_rows() {
        String missing = store();
        String legacyDetached = store();
        String legacyDraining = store();
        var jdbc = new JdbcTemplate(data);
        jdbc.update(
                "INSERT INTO ord_points_integration_policy(store_no,scene,mode,version)"
                        + " VALUES(?,?,?,?)",
                legacyDetached,
                "ONLINE",
                "DETACHED",
                0);
        jdbc.update(
                "INSERT INTO ord_points_integration_policy(store_no,scene,mode,version)"
                        + " VALUES(?,?,?,?)",
                legacyDraining,
                "OFFLINE",
                "DRAINING",
                1);

        for (String scene : List.of("ONLINE", "OFFLINE")) {
            assertParticipating(policy.policy(missing, scene));
        }
        assertParticipating(policy.policy(legacyDetached, "ONLINE"));
        assertParticipating(policy.policy(legacyDraining, "OFFLINE"));
    }

    @Test
    void rejects_single_store_policy_writes_and_unknown_scenes() {
        String store = store();
        var command =
                new IntegrationPolicyChange(
                        "REQUEST", store, "ONLINE", Mode.DETACHED, false, false, 0, "QA");
        assertThatThrownBy(() -> policy.change(command, "OPS"))
                .hasMessage("POINTS_STORE_POLICY_UNSUPPORTED");
        assertThatThrownBy(() -> policy.policy(store, "ONLNE"))
                .hasMessage("POINTS_INTEGRATION_INPUT_INVALID");
    }

    private void assertParticipating(IntegrationPolicy result) {
        assertThat(result.mode()).isEqualTo(Mode.ACTIVE);
        assertThat(result.earnEnabled()).isTrue();
        assertThat(result.redeemEnabled()).isTrue();
        assertThat(result.version()).isZero();
    }

    private String store() {
        return "ST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
