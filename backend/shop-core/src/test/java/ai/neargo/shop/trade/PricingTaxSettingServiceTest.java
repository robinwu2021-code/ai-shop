package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.when;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort.Scope;
import ai.neargo.shop.trade.port.PricingTaxSettingPortImpl;
import ai.neargo.shop.trade.port.PricingTaxCalculationPortImpl;
import ai.neargo.shop.trade.service.impl.PricingTaxCalculationServiceImpl;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.PessimisticLockingFailureException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.platform.impl.SettingServiceImpl;
import ai.neargo.shop.platform.mapper.PlatformMappers.SettingMapper;
import ai.neargo.shop.platform.port.SettingPortImpl;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.spi.platform.SettingPort.VersionedValue;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.ResetReq;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.SaveReq;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.SettingVO;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.Source;
import ai.neargo.shop.trade.service.PricingTaxSettingService;
import ai.neargo.shop.trade.service.impl.PricingTaxSettingServiceImpl;

/** Real sys_setting uniqueness, row locks, receipts and rollback; no business database credentials. */
@EnabledIfEnvironmentVariable(named = "PRICING_TEST_DISPOSABLE", matches = "true")
class PricingTaxSettingServiceTest {
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static SettingServiceImpl storage;
    private static SettingPort settings;
    private static IdempotencyService idempotency;
    private static PricingTaxSettingService service;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void setup() throws Exception {
        String url = System.getenv("PRICING_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/pricing_test_[0-9a-f]+");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
        root.execute("CREATE DATABASE " + url.substring(url.lastIndexOf('/') + 1) + "_tax");
        var data = new DriverManagerDataSource(url + "_tax", "root", "");
        jdbc = new JdbcTemplate(data);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).contains("MariaDB");
        String ddl = Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V8__sys_setting.sql"));
        int start = ddl.indexOf("CREATE TABLE");
        jdbc.execute(ddl.substring(start, ddl.indexOf(';', start)));
        jdbc.execute("CREATE TABLE sys_idempotent(id BIGINT AUTO_INCREMENT PRIMARY KEY,idem_key VARCHAR(128),"
                + "endpoint VARCHAR(128),user_no VARCHAR(64),request_hash CHAR(64),result_json LONGTEXT,"
                + "created_at DATETIME,expire_at DATETIME,UNIQUE(idem_key,endpoint)) ENGINE=InnoDB");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(SettingMapper.class);
        config.addMapper(SysIdempotentMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(data);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        manager = new DataSourceTransactionManager(data);
        storage = new SettingServiceImpl(session.getMapper(SettingMapper.class));
        settings = new SettingPortImpl(storage);
        idempotency = new IdempotencyService(session.getMapper(SysIdempotentMapper.class), JSON);
        service = build(settings, mock(AuditLogPort.class));
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM sys_idempotent");
        jdbc.update("DELETE FROM sys_setting");
    }

    @Test
    void persistedTaxSourcePreviewConfirmAndSameValueResetRequireNewCalculation() {
        var stores = mock(StoreDirectoryPort.class);
        when(stores.cards(List.of("S1"))).thenReturn(Map.of("S1", new StoreDirectoryPort.StoreCard(
                "S1", "Store", "M1", "", "ACTIVE", true, "", "", null, null, 0, 0)));
        var source = new PricingTaxSettingPortImpl(service, stores);
        var calculation = new PricingTaxCalculationPortImpl(new PricingTaxCalculationServiceImpl(source));
        var scope = new Scope("MAIN", "M1", "S1");
        defaults(0, 500, PriceTaxMode.EXCLUSIVE, "D1");
        var input = PricingTaxCalculationTest.input(10000, 1);
        var preview = calculation.estimate(scope, input);
        assertThat(calculation.confirm(scope, input, preview).result().invoiceGross().minor()).isEqualTo(10500);
        save("M1", "S1", 0, 500, PriceTaxMode.EXCLUSIVE, "M1");
        assertThatThrownBy(() -> calculation.confirm(scope, input, preview)).isInstanceOf(BizException.class);
        var custom = calculation.estimate(scope, input);
        service.resetMerchant(merchant("M1", "S1"), new ResetReq(1), "RESET");
        assertThatThrownBy(() -> calculation.confirm(scope, input, custom)).isInstanceOf(BizException.class);
        assertThat(source.resolve(scope).managedVersion()).isEqualTo(2);
        assertThat(preview.result().invoiceTax().minor()).isEqualTo(500);
        defaults(1, 600, PriceTaxMode.EXCLUSIVE, "D2");
        assertThat(calculation.estimate(scope, input).result().invoiceTax().minor()).isEqualTo(600);
    }

    @Test
    void missingDefaultIsVisibleButCannotBeAssumedToBeZeroTax() {
        assertThat(assertDoesNotThrow(() -> service.getPlatform(platform())).effective()).isNull();
        assertThat(view("M1", "S1").effective()).isNull();
        assertThatThrownBy(() -> service.requireEffective(merchant("M1", "S1")))
                .isInstanceOfSatisfying(BizException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        assertThat(count("sys_setting")).isZero();
    }

    @Test
    void defaultsAndMerchantOverridesRemainIndependentAcrossUpdatesAndStores() {
        var first = defaults(0, 500, PriceTaxMode.EXCLUSIVE, "D1");
        assertThat(first.version()).isEqualTo(1);
        assertThat(view("M1", "S1").effective()).isEqualTo(first.effective());
        var custom = save("M1", "S1", 0, 0, PriceTaxMode.INCLUSIVE, "M1");
        assertThat(custom.effective().source()).isEqualTo(Source.MERCHANT_CUSTOM);
        assertThat(custom.effective().rateBp()).isZero();
        assertThat(view("M1", "S2")).isEqualTo(custom);
        var second = defaults(1, 600, PriceTaxMode.INCLUSIVE, "D2");
        assertThat(view("M2", "S2").effective()).isEqualTo(second.effective());
        assertThat(view("M1", "S1")).isEqualTo(custom);
        assertThat(service.requireEffective(merchant("M1", "S1"))).isEqualTo(custom.effective());
    }

    @Test
    void resetPersistsAnIncreasingVersionAndUsesTheCurrentDefault() {
        defaults(0, 500, PriceTaxMode.EXCLUSIVE, "D1");
        save("M1", "S1", 0, 800, PriceTaxMode.INCLUSIVE, "M1");
        var latest = defaults(1, 900, PriceTaxMode.EXCLUSIVE, "D2");
        var reset = assertDoesNotThrow(() -> service.resetMerchant(merchant("M1", "S1"), new ResetReq(1), "R1"));
        assertThat(reset.version()).isEqualTo(2);
        assertThat(reset.custom()).isFalse();
        assertThat(reset.effective()).isEqualTo(latest.effective());
        assertThat(count("sys_setting")).isEqualTo(2);
        assertThatThrownBy(() -> service.saveMerchant(merchant("M1", "S1"),
                new SaveReq(0, 700, PriceTaxMode.EXCLUSIVE), "OLD"))
                .isInstanceOf(BizException.class);
        var resetEmpty = assertDoesNotThrow(() -> service.resetMerchant(merchant("M2", "S2"),
                new ResetReq(0), "EMPTY_RESET"));
        assertThat(resetEmpty.version()).isEqualTo(1);
        assertThat(resetEmpty.custom()).isFalse();
    }

    @Test
    void oldSuccessfulReceiptCannotOverwriteANewerSetting() {
        var first = save("M1", "S1", 0, 500, PriceTaxMode.EXCLUSIVE, "FIRST");
        var latest = save("M1", "S1", 1, 600, PriceTaxMode.INCLUSIVE, "NEXT");
        assertThat(save("M1", "S1", 0, 500, PriceTaxMode.EXCLUSIVE, "FIRST")).isEqualTo(first);
        assertThat(view("M1", "S1")).isEqualTo(latest);
        assertThatThrownBy(() -> service.saveMerchant(merchant("M1", "S1"),
                new SaveReq(0, 700, PriceTaxMode.EXCLUSIVE), "FIRST"))
                .isInstanceOf(BizException.class);
        assertThat(view("M1", "S1")).isEqualTo(latest);
    }

    @Test
    void completedReceiptReplaysWithoutAnAvailableSettingsProviderAndStillChecksTheActor() {
        var original = save("M1", "S1", 0, 500, PriceTaxMode.EXCLUSIVE, "SAVED");
        var unavailable = build(new SettingPort() {
            @Override public String get(String key, String fallback) { throw new AssertionError("legacy read"); }
            @Override public void put(String key, String value, String actor) { throw new AssertionError("write"); }
            @Override public Optional<VersionedValue> getVersioned(String key) {
                throw new AssertionError("Completed receipt must not contact the source");
            }
        }, mock(AuditLogPort.class));
        assertThat(assertDoesNotThrow(() -> unavailable.saveMerchant(merchant("M1", "S1"),
                new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "SAVED"))).isEqualTo(original);
        var otherActor = new Context("MAIN", "OTHER", false, "M1", "S1",
                new PricingCapabilities(0, 0, false, false, false), "DRAFT_ONLY", Instant.EPOCH, Set.of());
        assertThatThrownBy(() -> unavailable.saveMerchant(otherActor,
                new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "SAVED")).isInstanceOf(BizException.class);
    }

    @Test
    void bestEffortAuditFailureDoesNotTurnACommittedSettingIntoAFailedCommand() {
        var audit = new AuditLogPort() {
            @Override public void record(String action, String target, String detail) {
                throw new IllegalStateException("audit offline");
            }
            @Override public long countSince(String action, long since) { return 0; }
        };
        var active = build(settings, audit);
        var saved = assertDoesNotThrow(() -> active.saveMerchant(merchant("M1", "S1"),
                new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "AUDIT"));
        assertThat(view("M1", "S1")).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM sys_setting WHERE setting_key=?",
                String.class, "pricing.tax.merchant.M1")).isEqualTo("STAFF");
        assertThat(count("sys_idempotent")).isEqualTo(1);
    }

    @Test
    void auditTransactionRollbackCannotEraseAReportedSuccessfulSettingOrReceipt() {
        var audit = new AuditLogPort() {
            @Override public void record(String action, String target, String detail) {
                // Emulate a MariaDB deadlock victim: the server already rolled back the whole physical transaction.
                jdbc.execute("ROLLBACK");
                throw new PessimisticLockingFailureException("audit transaction was a deadlock victim");
            }
            @Override public long countSince(String action, long since) { return 0; }
        };
        var active = build(settings, audit);
        var saved = assertDoesNotThrow(() -> active.saveMerchant(merchant("M1", "S1"),
                new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "AUDIT_ROLLBACK"));
        assertThat(view("M1", "S1")).isEqualTo(saved);
        assertThat(count("sys_setting")).isEqualTo(1);
        assertThat(count("sys_idempotent")).isEqualTo(1);
    }

    @Test
    void auditWaitsForTheOuterCommitAndNeverRecordsRolledBackChanges() {
        var calls = new AtomicInteger();
        var audit = new AuditLogPort() {
            @Override public void record(String action, String target, String detail) { calls.incrementAndGet(); }
            @Override public long countSince(String action, long since) { return 0; }
        };
        var active = build(settings, audit);
        new TransactionTemplate(manager).execute(status -> {
            active.saveMerchant(merchant("M1", "S1"), new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "OUTER");
            assertThat(calls.get()).isZero();
            status.setRollbackOnly();
            return null;
        });
        assertThat(count("sys_setting")).isZero();
        assertThat(count("sys_idempotent")).isZero();
        assertThat(calls.get()).isZero();
        new TransactionTemplate(manager).execute(status -> {
            active.saveMerchant(merchant("M1", "S1"), new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "OUTER");
            assertThat(calls.get()).isZero();
            return null;
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(count("sys_setting")).isEqualTo(1);
    }

    @Test
    void legacyCreationUsesAVisibleVersionAndCannotOverwriteAnUnseenNewSetting() {
        assertThat(assertDoesNotThrow(() -> service.getPlatform(platform())).version()).isZero();
        assertDoesNotThrow(() -> new TransactionTemplate(manager).execute(status -> {
            storage.put("pricing.tax.default",
                    "{\"schemaVersion\":1,\"custom\":true,\"rateBp\":600,\"priceTaxMode\":\"INCLUSIVE\"}",
                    "LEGACY");
            return null;
        }));
        assertThat(settings.getVersioned("pricing.tax.default").orElseThrow().version()).isEqualTo(1);
        assertThatThrownBy(() -> service.savePlatform(platform(), new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE),
                "UNSEEN")).isInstanceOfSatisfying(BizException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(service.getPlatform(platform()).effective().rateBp()).isEqualTo(600);
    }

    @Test
    void firstCreateAndExistingUpdatesHaveOnlyOneCasWinner() throws Exception {
        assertThat(view("M1", "S1").version()).isZero();
        assertThat(compete(() -> service.saveMerchant(merchant("M1", "S1"),
                new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "CREATE_A"),
                () -> service.saveMerchant(merchant("M1", "S1"),
                        new SaveReq(0, 600, PriceTaxMode.EXCLUSIVE), "CREATE_B")))
                .containsExactlyInAnyOrder(true, false);
        assertThat(view("M1", "S1").version()).isEqualTo(1);
        assertThat(compete(() -> service.saveMerchant(merchant("M1", "S1"),
                new SaveReq(1, 700, PriceTaxMode.EXCLUSIVE), "UPDATE_A"),
                () -> service.saveMerchant(merchant("M1", "S1"),
                        new SaveReq(1, 800, PriceTaxMode.EXCLUSIVE), "UPDATE_B")))
                .containsExactlyInAnyOrder(true, false);
        assertThat(view("M1", "S1").version()).isEqualTo(2);
    }

    @Test
    void concurrentSameKeyReturnsOneSuccessfulReceiptWithoutDoubleWriting() throws Exception {
        defaults(0, 500, PriceTaxMode.EXCLUSIVE, "D1");
        assertThat(compete(() -> service.saveMerchant(merchant("M1", "S1"),
                new SaveReq(0, 600, PriceTaxMode.INCLUSIVE), "SAME"),
                () -> service.saveMerchant(merchant("M1", "S1"),
                        new SaveReq(0, 600, PriceTaxMode.INCLUSIVE), "SAME")))
                .containsExactly(true, true);
        assertThat(view("M1", "S1").version()).isEqualTo(1);
        assertThat(count("sys_idempotent")).isEqualTo(2);
    }

    @Test
    void versionedStorageChecksOriginalJsonAndLegacyPutAlwaysAdvancesTheVersion() {
        var first = defaults(0, 500, PriceTaxMode.EXCLUSIVE, "D1");
        String key = "pricing.tax.default";
        VersionedValue original = assertDoesNotThrow(() -> settings.getVersioned(key)).orElseThrow();
        assertThat(original.version()).isEqualTo(first.version());
        new TransactionTemplate(manager).execute(status -> {
            storage.put(key, original.json(), "LEGACY");
            return null;
        });
        assertThat(settings.getVersioned(key).orElseThrow().version()).isGreaterThan(original.version());
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status ->
                settings.compareAndSet(key, original, original.json(), "STALE")))
                .isInstanceOf(BizException.class);
        for (String large : List.of("x".repeat(65536), "税".repeat(30000))) {
            assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status ->
                    settings.compareAndSet("versioned.too-large", null, large, "ACTOR")))
                    .isInstanceOfSatisfying(BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
        var current = settings.getVersioned(key).orElseThrow();
        jdbc.update("UPDATE sys_setting SET setting_value=? WHERE setting_key=?",
                current.json().replace("EXCLUSIVE", "exclusive"), key);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status ->
                settings.compareAndSet(key, current, original.json(), "STALE_BYTES")))
                .isInstanceOf(BizException.class);
        jdbc.update("UPDATE sys_setting SET setting_value=?,version=0 WHERE setting_key=?", original.json(), key);
        assertThat(settings.getVersioned(key).orElseThrow().version()).isZero();
        assertThat(service.getPlatform(platform()).effective().rateBp()).isEqualTo(500);
    }

    @Test
    void aFailedMutationRollsBackBothSettingAndReceipt() {
        defaults(0, 500, PriceTaxMode.EXCLUSIVE, "D1");
        var failing = build(new SettingPortImpl(storage) {
            @Override
            public VersionedValue compareAndSet(String key, VersionedValue expected, String json, String actor) {
                super.compareAndSet(key, expected, json, actor);
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }, mock(AuditLogPort.class));
        assertThatThrownBy(() -> failing.saveMerchant(merchant("M1", "S1"),
                new SaveReq(0, 800, PriceTaxMode.EXCLUSIVE), "FAIL"))
                .isInstanceOf(BizException.class);
        assertThat(count("sys_setting")).isEqualTo(1);
        assertThat(count("sys_idempotent")).isEqualTo(1);
        assertThat(save("M1", "S1", 0, 800, PriceTaxMode.EXCLUSIVE, "FAIL").version()).isEqualTo(1);
    }

    @Test
    void malformedMerchantOrPlatformSettingsCannotFallBackToZeroOrAnotherSource() {
        defaults(0, 500, PriceTaxMode.EXCLUSIVE, "D1");
        save("M1", "S1", 0, 700, PriceTaxMode.EXCLUSIVE, "M1");
        jdbc.update("UPDATE sys_setting SET setting_value='{}' WHERE setting_key=?", "pricing.tax.merchant.M1");
        assertThatThrownBy(() -> service.getMerchant(merchant("M1", "S1")))
                .isInstanceOfSatisfying(BizException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        jdbc.update("UPDATE sys_setting SET setting_value=? WHERE setting_key=?",
                "{\"schemaVersion\":1,\"custom\":false,\"rateBp\":null,\"priceTaxMode\":null}",
                "pricing.tax.default");
        assertThatThrownBy(() -> service.getMerchant(merchant("M2", "S2")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void realmScopeAndInvalidVersionsAreRejectedBeforeWriting() {
        defaults(0, 500, PriceTaxMode.EXCLUSIVE, "D1");
        assertThatThrownBy(() -> service.savePlatform(merchant("M1", "S1"),
                new SaveReq(1, 500, PriceTaxMode.EXCLUSIVE), "X")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.saveMerchant(platform(),
                new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "Y")).isInstanceOf(BizException.class);
        for (long version : new long[] {-1, 9007199254740991L, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> service.saveMerchant(merchant("M1", "S1"),
                    new SaveReq(version, 500, PriceTaxMode.EXCLUSIVE), "V" + version))
                    .isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> service.saveMerchant(merchant("M1", "S1"),
                new SaveReq(0, 10001, PriceTaxMode.EXCLUSIVE), "RATE")).isInstanceOf(BizException.class);
        assertThat(count("sys_setting")).isEqualTo(1);
    }

    @Test
    void lockRetriesAreBoundedAndNeverRetryInsideAnAmbientTransaction() {
        var attempts = new AtomicInteger();
        var transientPort = new SettingPortImpl(storage) {
            @Override
            public VersionedValue compareAndSet(String key, VersionedValue expected, String value, String actor) {
                if (attempts.incrementAndGet() <= 2) throw new PessimisticLockingFailureException("transient lock");
                return super.compareAndSet(key, expected, value, actor);
            }
        };
        var recovering = build(transientPort, mock(AuditLogPort.class));
        assertThat(assertDoesNotThrow(() -> recovering.saveMerchant(merchant("M1", "S1"),
                new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "RETRY")).version()).isEqualTo(1);
        assertThat(attempts.get()).isEqualTo(3);
        attempts.set(0);
        var unavailable = build(new SettingPortImpl(storage) {
            @Override
            public VersionedValue compareAndSet(String key, VersionedValue expected, String value, String actor) {
                attempts.incrementAndGet();
                throw new PessimisticLockingFailureException("persistent lock");
            }
        }, mock(AuditLogPort.class));
        assertThatThrownBy(() -> unavailable.saveMerchant(merchant("M2", "S2"),
                new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "EXHAUSTED"))
                .isInstanceOfSatisfying(BizException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(attempts.get()).isEqualTo(3);
        attempts.set(0);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status ->
                unavailable.saveMerchant(merchant("M3", "S3"),
                        new SaveReq(0, 500, PriceTaxMode.EXCLUSIVE), "AMBIENT")))
                .isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(count("sys_setting")).isEqualTo(1);
        assertThat(count("sys_idempotent")).isEqualTo(1);
    }

    @Test
    void snapshotsAreStableAndDoNotChangeWhenNewDefaultsAreSaved() {
        var original = defaults(0, 500, PriceTaxMode.EXCLUSIVE, "D1").effective();
        assertThat(view("M1", "S1").effective()).isEqualTo(original);
        defaults(1, 600, PriceTaxMode.INCLUSIVE, "D2");
        assertThat(original.rateBp()).isEqualTo(500);
        assertThat(original.priceTaxMode()).isEqualTo(PriceTaxMode.EXCLUSIVE);
        assertThat(view("M1", "S1").effective().contentHash()).isNotEqualTo(original.contentHash());
    }

    private static PricingTaxSettingService build(SettingPort port, AuditLogPort audit) {
        return new PricingTaxSettingServiceImpl(port, audit, idempotency, manager, JSON);
    }

    private SettingVO defaults(long version, int rate, PriceTaxMode mode, String key) {
        return assertDoesNotThrow(() -> service.savePlatform(platform(), new SaveReq(version, rate, mode), key));
    }

    private SettingVO save(String entity, String store, long version, int rate, PriceTaxMode mode, String key) {
        return assertDoesNotThrow(() -> service.saveMerchant(merchant(entity, store),
                new SaveReq(version, rate, mode), key));
    }

    private SettingVO view(String entity, String store) {
        return assertDoesNotThrow(() -> service.getMerchant(merchant(entity, store)));
    }

    private static Context platform() {
        return new Context("MAIN", "OPS", true, null, null, new PricingCapabilities(0, 0, false, false, false),
                "DRAFT_ONLY", Instant.EPOCH, Set.of());
    }

    private static Context merchant(String entity, String store) {
        return new Context("MAIN", "STAFF", false, entity, store, new PricingCapabilities(0, 0, false, false, false),
                "DRAFT_ONLY", Instant.EPOCH, Set.of());
    }

    private static long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private List<Boolean> compete(Callable<SettingVO> a, Callable<SettingVO> b) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(a, b).stream().map(action -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                try {
                    action.call();
                    return true;
                } catch (BizException conflict) {
                    assertThat(conflict.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    return false;
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }
}
