package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import ai.neargo.shop.support.StandalonePayFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real separate JVMs and databases, using the same merchant workflow as the embedded acceptance scenario. */
@EnabledIfEnvironmentVariable(named = "ENTITLEMENT_DUAL_PROCESS", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "shop.member-code.enabled=true", "shop.core.entitlement.authorization.enabled=true",
        "shop.promotion.activity.enabled=true", "shop.promotion.presets.enabled=true",
        "shop.pay.stored-value.enabled=true", "shop.pay.entitlement.use.enabled=true",
        "shop.pay.entitlement.cursor-key=dual-process-test-cursor-key-at-least-32-characters"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EntitlementTwoProcessAcceptanceTest extends BeautyStoredValueLifecycleFlowTest {
    private static final StandalonePayFixture PAY =
            new StandalonePayFixture();
    @DynamicPropertySource static void processConfiguration(DynamicPropertyRegistry properties) throws Exception {
        PAY.start(properties, "dual-core");
    }
    @Override protected JdbcTemplate payJdbc() { return PAY.jdbc(); }

    @AfterEach void ownerWritesStayInThePayProcessAndCommittedEventsReachCoreOverHttp() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_use_intent", Integer.class)).isZero();
        assertThat(PAY.jdbc().queryForObject("SELECT COUNT(*) FROM sv_account", Integer.class)).isEqualTo(1);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (PAY.jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE status<>'SENT'", Integer.class) > 0
                && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(PAY.jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE status<>'SENT'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE event_type='STORED_VALUE_CREDITED'",
                Integer.class)).isEqualTo(2);
    }
    @AfterAll static void stopFixture() throws Exception { PAY.close(); }
}
