package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.support.StandalonePayFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Reuse all three real checkout/consumption models with a separate, unmocked payment JVM and database. */
@EnabledIfEnvironmentVariable(named = "ENTITLEMENT_DUAL_PROCESS", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "shop.member-code.enabled=true", "shop.core.entitlement.authorization.enabled=true",
        "shop.pay.entitlement.catalog.enabled=true", "shop.pay.entitlement.issue.enabled=true",
        "shop.pay.entitlement.use.enabled=true", "shop.pay.paid-card.enabled=true",
        "shop.trade.paid-card-sale.enabled=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaidCardTwoProcessAcceptanceTest extends PaidCardCheckoutFlowTest {
    private static final StandalonePayFixture PAY = new StandalonePayFixture();
    @DynamicPropertySource static void processConfiguration(DynamicPropertyRegistry properties) throws Exception {
        PAY.start(properties, "dual-card-core");
    }
    @Override protected JdbcTemplate payJdbc() { return PAY.jdbc(); }
    @AfterEach void ownerRightsAndUsesWereNeverWrittenToCoreDatabase() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_use_intent", Integer.class)).isZero();
        assertThat(PAY.jdbc().queryForObject("SELECT COUNT(*) FROM ent_usage", Integer.class)).isPositive();
    }
    @AfterAll static void stopFixture() throws Exception { PAY.close(); }
}
