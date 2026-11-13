package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** In-memory wiring/transaction evidence; it does not substitute for MySQL9 native verification. */
class PaymentCollectionSourceBootstrapTest {
    DataSource dataSource;
    JdbcTemplate jdbc;
    ApplicationContextRunner context;

    @BeforeEach
    void setup() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:source-bootstrap-" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        context = new ApplicationContextRunner()
                .withUserConfiguration(PaymentCollectionSourceConfiguration.class)
                .withBean("dataSource", DataSource.class, () -> dataSource)
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .withPropertyValues("shop.payment-collections.enabled=true");
    }

    void schema() {
        jdbc.execute("CREATE TABLE mch_entity (entity_no VARCHAR(64) PRIMARY KEY,"
                + " market VARCHAR(8),deleted INT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE mch_store (id BIGINT PRIMARY KEY,store_no VARCHAR(64),"
                + " entity_no VARCHAR(64),deleted INT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE payment_collection_source (environment VARCHAR(16),"
                + " pay_app_id BIGINT,source_type VARCHAR(16),source_id BIGINT,"
                + " payment_store_id BIGINT,store_no VARCHAR(64),entity_no VARCHAR(64),"
                + " enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                + " PRIMARY KEY(environment,pay_app_id,source_type,source_id,payment_store_id))");
        jdbc.update("INSERT INTO mch_entity VALUES ('E1','TW',0),('E2','TW',0)");
        jdbc.update("INSERT INTO mch_store VALUES (101,'S1','E1',0),(102,'S2','E2',0)");
    }

    String[] binding(int index, long paymentStoreId, long shopStoreId, String storeNo,
            String entityNo) {
        String p = "shop.payment-collections.source-bindings[" + index + "].";
        return new String[] {p + "environment=SANDBOX",p + "pay-app-id=42",
                p + "source-type=CHECKOUT",p + "source-id=42",
                p + "payment-store-id=" + paymentStoreId,p + "shop-store-id=" + shopStoreId,
                p + "store-no=" + storeNo,p + "entity-no=" + entityNo,p + "market=TW"};
    }

    @Test
    void emptyConfigurationDoesNotRequireAnyCollectionOrMerchantTable() {
        context.run(c -> assertThat(c).hasNotFailed());
    }

    @Test
    void disabledFeatureDoesNotProvisionEvenWhenMappingsArePresent() {
        context.withPropertyValues(binding(0,9001,101,"S1","E1"))
                .withPropertyValues("shop.payment-collections.enabled=false")
                .run(c -> assertThat(c).hasNotFailed());
    }

    @Test
    void createsCrossIdMappingAndIdenticalRestartIsIdempotent() {
        schema();
        var configured = context.withPropertyValues(binding(0,9001,101,"S1","E1"));
        configured.run(c -> assertThat(c).hasNotFailed());
        configured.run(c -> assertThat(c).hasNotFailed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_collection_source",Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payment_store_id FROM payment_collection_source",Long.class))
                .isEqualTo(9001);
        assertThat(jdbc.queryForObject("SELECT store_no FROM payment_collection_source",String.class))
                .isEqualTo("S1");
    }

    @Test
    void emptyConfigurationPreservesExistingDisabledAndUnrelatedMappings() {
        schema();
        jdbc.update("INSERT INTO payment_collection_source VALUES"
                + " ('PRODUCTION',4,'BIZ',5,6,'S1','E1',FALSE)");
        context.run(c -> assertThat(c).hasNotFailed());
        assertThat(jdbc.queryForObject("SELECT enabled FROM payment_collection_source",Boolean.class))
                .isFalse();
    }

    @Test
    void conflictingTargetRollsBackTheWholeBatchWithoutOverwriting() {
        schema();
        jdbc.update("INSERT INTO payment_collection_source VALUES"
                + " ('SANDBOX',42,'CHECKOUT',42,9002,'S2','E2',TRUE)");
        context.withPropertyValues(binding(0,9001,101,"S1","E1"))
                .withPropertyValues(binding(1,9002,101,"S1","E1"))
                .run(c -> assertThat(c).hasFailed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_collection_source",Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT store_no FROM payment_collection_source",String.class))
                .isEqualTo("S2");
    }

    @Test
    void disabledMappingIsNotSilentlyReenabled() {
        schema();
        jdbc.update("INSERT INTO payment_collection_source VALUES"
                + " ('SANDBOX',42,'CHECKOUT',42,9001,'S1','E1',FALSE)");
        context.withPropertyValues(binding(0,9001,101,"S1","E1"))
                .run(c -> assertThat(c).hasFailed());
        assertThat(jdbc.queryForObject("SELECT enabled FROM payment_collection_source",Boolean.class))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE mch_store SET entity_no='E2' WHERE id=101",
            "UPDATE mch_store SET store_no='OTHER' WHERE id=101",
            "UPDATE mch_store SET deleted=1 WHERE id=101",
            "UPDATE mch_entity SET deleted=1 WHERE entity_no='E1'",
            "UPDATE mch_entity SET market='AE' WHERE entity_no='E1'"
    })
    void invalidCurrentStoreOrEntityCannotBeProvisioned(String change) {
        schema();jdbc.update(change);
        context.withPropertyValues(binding(0,9001,101,"S1","E1"))
                .run(c -> assertThat(c).hasFailed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_collection_source",Integer.class))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"environment=PRODUCTION","source-type=BIZ","source-id=43",
            "pay-app-id=0","payment-store-id=0","shop-store-id=0","market=AE"})
    void unsupportedOrInvalidTrustedConfigurationFailsBeforeWriting(String override) {
        schema();
        context.withPropertyValues(binding(0,9001,101,"S1","E1"))
                .withPropertyValues("shop.payment-collections.source-bindings[0]." + override)
                .run(c -> assertThat(c).hasFailed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_collection_source",Integer.class))
                .isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void successfulAuditContainsOnlySafeCoordinatesAndFingerprint(CapturedOutput output) {
        schema();
        context.withPropertyValues(binding(0,9001,101,"S1","E1"))
                .withPropertyValues("shop.payment-collections.secret=must-not-be-logged",
                        "shop.payment-collections.cursor-secret=also-must-not-be-logged")
                .run(c -> assertThat(c).hasNotFailed());
        assertThat(output.getAll()).contains("environment=SANDBOX", "paymentStoreId=9001",
                        "shopStoreId=101", "storeNo=S1", "entityNo=E1", "fingerprint=")
                .doesNotContain("must-not-be-logged", "cursor-secret=");
    }

    @Test
    void bootstrapCommitsBeforeAnOutboxLifecycleCanStart() {
        schema();var started = new AtomicBoolean();
        context.withPropertyValues(binding(0,9001,101,"S1","E1"))
                .withBean("testOutboxLifecycle",SmartLifecycle.class,() -> new SmartLifecycle() {
                    public void start() {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .isFalse();
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_collection_source",
                                Integer.class)).isEqualTo(1);
                        started.set(true);
                    }
                    public void stop() {started.set(false);}
                    public boolean isRunning() {return started.get();}
                })
                .run(c -> {assertThat(c).hasNotFailed();assertThat(started).isTrue();});
    }

    @Test
    void bootstrapFailurePreventsOutboxLifecycleStartup() {
        schema();jdbc.update("UPDATE mch_store SET deleted=1 WHERE id=101");
        var started = new AtomicBoolean();
        context.withPropertyValues(binding(0,9001,101,"S1","E1"))
                .withBean("testOutboxLifecycle",SmartLifecycle.class,() -> new SmartLifecycle() {
                    public void start() {started.set(true);}
                    public void stop() {}
                    public boolean isRunning() {return started.get();}
                })
                .run(c -> {assertThat(c).hasFailed();assertThat(started).isFalse();});
    }
}
