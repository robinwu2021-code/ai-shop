package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** H2 by default; explicit MySQL mode can only address one disposable local test schema. */
class PaymentCollectionSourceBootstrapTest {
    DataSource dataSource;
    JdbcTemplate jdbc;
    ApplicationContextRunner context;
    boolean nativeMysql;

    @BeforeEach
    void setup() {
        String url = System.getenv("PAYMENT_SOURCE_BOOTSTRAP_JDBC_URL");
        String user = System.getenv("PAYMENT_SOURCE_BOOTSTRAP_DB_USER");
        String password = System.getenv("PAYMENT_SOURCE_BOOTSTRAP_DB_PASSWORD");
        nativeMysql = url != null;
        if (nativeMysql) {
            if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/"
                    + "payment_source_bootstrap_[a-f0-9]{16,64}"
                    + "(?:\\?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC)?")
                    || user == null || user.isBlank() || password == null || password.isBlank()) {
                throw new IllegalArgumentException("Only an explicit disposable local MySQL9 schema is allowed");
            }
            dataSource = new DriverManagerDataSource(url, user, password);
        } else {
            if (user != null || password != null) {
                throw new IllegalArgumentException("MySQL credentials require the restricted test URL");
            }
            dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:source-bootstrap-" + UUID.randomUUID()
                            + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        jdbc = new JdbcTemplate(dataSource);
        if (nativeMysql) {
            assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
            clearOwnTables();
        }
        context = new ApplicationContextRunner()
                .withUserConfiguration(PaymentCollectionSourceConfiguration.class)
                .withBean("dataSource", DataSource.class, () -> dataSource)
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .withPropertyValues("shop.payment-collections.enabled=true");
    }

    @AfterEach
    void cleanupNativeTables() {
        if (nativeMysql && jdbc != null) {
            clearOwnTables();
        }
    }

    private void clearOwnTables() {
        for (String table : List.of("payment_collection_source", "mch_store", "mch_entity")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    void schema() {
        String options = nativeMysql
                ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin" : "";
        jdbc.execute("CREATE TABLE mch_entity (entity_no VARCHAR(64) PRIMARY KEY,"
                + " market VARCHAR(8),deleted INT NOT NULL DEFAULT 0)" + options);
        jdbc.execute("CREATE TABLE mch_store (id BIGINT PRIMARY KEY,store_no VARCHAR(64),"
                + " entity_no VARCHAR(64),deleted INT NOT NULL DEFAULT 0)" + options);
        jdbc.execute("CREATE TABLE payment_collection_source (environment VARCHAR(16),"
                + " pay_app_id BIGINT,source_type VARCHAR(16),source_id BIGINT,"
                + " payment_store_id BIGINT,store_no VARCHAR(64),entity_no VARCHAR(64),"
                + " enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                + " PRIMARY KEY(environment,pay_app_id,source_type,source_id,payment_store_id))" + options);
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
    void concurrentSameTargetOnTwoConnectionsCreatesOnlyOneMapping() throws Exception {
        schema();
        var outcomes = concurrently(bindingValue(101, "S1", "E1"), bindingValue(101, "S1", "E1"));
        assertThat(outcomes).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_collection_source", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentConflictingTargetsNeverOverwriteTheWinningMapping() throws Exception {
        schema();
        var outcomes = concurrently(bindingValue(101, "S1", "E1"), bindingValue(102, "S2", "E2"));
        assertThat(outcomes.stream().filter(e -> e == null).count()).isEqualTo(1);
        var failure = outcomes.stream().filter(e -> e != null).findFirst().orElseThrow();
        assertThat(failure).hasMessageStartingWith("Payment collection source mapping conflict:");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_collection_source", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT store_no FROM payment_collection_source", String.class))
                .isEqualTo(outcomes.getFirst() == null ? "S1" : "S2");
    }

    private PaymentCollectionSourceProperties.Binding bindingValue(long shopId, String store,
            String entity) {
        return new PaymentCollectionSourceProperties.Binding(
                "SANDBOX", 42, "CHECKOUT", 42, 9001, shopId, store, entity, "TW");
    }

    private List<IllegalStateException> concurrently(PaymentCollectionSourceProperties.Binding first,
            PaymentCollectionSourceProperties.Binding second) throws Exception {
        try (var left = dataSource.getConnection(); var right = dataSource.getConnection();
                var workers = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var connections = List.of(left, right);
            var values = List.of(first, second);
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
                var ds = new SingleConnectionDataSource(connections.get(index), true);
                var bootstrap = new PaymentCollectionSourceBootstrap(
                        new PaymentCollectionSourceProperties(List.of(values.get(index))), ds,
                        new DataSourceTransactionManager(ds));
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent bootstrap start timed out");
                }
                try {
                    bootstrap.provision();
                    return (IllegalStateException) null;
                } catch (IllegalStateException conflict) {
                    return conflict;
                }
            })).toList();
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return java.util.Arrays.asList(futures.get(0).get(30, TimeUnit.SECONDS),
                    futures.get(1).get(30, TimeUnit.SECONDS));
        }
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
