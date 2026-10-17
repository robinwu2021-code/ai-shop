package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOrderSourceTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointsOrderIntegrationPolicyTest {
    @Test
    void native_concurrent_policy_cas_has_one_winner() throws Exception {
        try (var connection = data.getConnection()) {
            Assumptions.assumeTrue(
                    connection.getMetaData().getDatabaseProductName().contains("MySQL"));
        }
        String a = store();
        policy.change(command("INIT:" + a, a, Mode.ACTIVE, true, -1), "MAKER");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks =
                    List.of("A", "B").stream()
                            .map(
                                    id ->
                                            pool.submit(
                                                    () -> {
                                                        barrier.await();
                                                        try {
                                                            policy.change(
                                                                    command(
                                                                            id + ":" + a,
                                                                            a,
                                                                            Mode.DRAINING,
                                                                            false,
                                                                            0),
                                                                    "MAKER");
                                                            return true;
                                                        } catch (
                                                                IllegalArgumentException conflict) {
                                                            assertThat(conflict)
                                                                    .hasMessageContaining(
                                                                            "REVISION");
                                                            return false;
                                                        }
                                                    }))
                            .toList();
            int wins = 0;
            for (var task : tasks) if (task.get(15, java.util.concurrent.TimeUnit.SECONDS)) wins++;
            assertThat(wins).isEqualTo(1);
            assertThat(policy.policy(a, "ONLINE").version()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Autowired PointsIntegrationPolicyService policy;
    @Autowired DataSource data;

    @BeforeAll
    void schema() {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V427__order_points_integration.sql"))
                .execute(data);
    }

    private String store() {
        return "ST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private IntegrationPolicyChange command(
            String id, String store, Mode mode, boolean earn, long version) {
        return new IntegrationPolicyChange(id, store, "ONLINE", mode, earn, false, version, "QA");
    }

    @Test
    void default_is_detached_and_enabling_one_store_does_not_enable_another() {
        String a = store(), b = store();
        assertThat(policy.policy(a, "ONLINE").mode()).isEqualTo(Mode.DETACHED);
        var active = policy.change(command("A:" + a, a, Mode.ACTIVE, true, -1), "MAKER");
        assertThat(active.version()).isZero();
        assertThat(active.actor()).isEqualTo("MAKER");
        assertThat(policy.policy(b, "ONLINE").mode()).isEqualTo(Mode.DETACHED);
    }

    @Test
    void audit_replay_retains_time_and_rejects_altered_body_or_actor() {
        String a = store();
        var change = command("A:" + a, a, Mode.ACTIVE, true, -1);
        var result = policy.change(change, "MAKER");
        assertThat(policy.change(change, "MAKER")).isEqualTo(result);
        assertThatThrownBy(() -> policy.change(change, "OTHER")).hasMessageContaining("CONFLICT");
        assertThatThrownBy(
                        () ->
                                policy.change(
                                        command(change.requestId(), a, Mode.DRAINING, false, 0),
                                        "MAKER"))
                .hasMessageContaining("CONFLICT");
        assertThat(policy.policy(a, "ONLINE")).isEqualTo(result);
    }

    @Test
    void rejects_stale_versions_unknown_scenes_and_unsafe_detach() {
        String a = store();
        policy.change(command("A:" + a, a, Mode.ACTIVE, true, -1), "MAKER");
        assertThatThrownBy(
                        () -> policy.change(command("B:" + a, a, Mode.ACTIVE, true, -1), "MAKER"))
                .hasMessageContaining("REVISION");
        var draining = policy.change(command("C:" + a, a, Mode.DRAINING, false, 0), "MAKER");
        assertThat(draining.mode()).isEqualTo(Mode.DRAINING);
        assertThatThrownBy(
                        () -> policy.change(command("D:" + a, a, Mode.DETACHED, false, 1), "MAKER"))
                .hasMessageContaining("OBLIGATIONS");
        assertThatThrownBy(() -> policy.policy(a, "ONLNE")).hasMessageContaining("INPUT");
    }
}
