package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelPolicyMapper;
import ai.neargo.shop.trade.service.IndustryChannelOrderPolicyService.EnabledReq;
import ai.neargo.shop.trade.service.IndustryChannelOrderPolicyService.SaveReq;
import ai.neargo.shop.trade.service.impl.IndustryChannelOrderPolicyServiceImpl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.UUID;

class IndustryChannelOrderPolicyTest {
    static class Owners implements MerchantScopeFactsPort {
        boolean unavailable;

        public boolean entityActive(String tenant, String entity) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (unavailable) throw new IllegalStateException("Original merchant owner unavailable");
            return "MAIN".equals(tenant) && "E".equals(entity);
        }

        public boolean storesOwned(String tenant, String entity, Set<String> stores) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "E".equals(entity) && stores.equals(Set.of("S"));
        }
    }

    final Scope scope = new Scope("MAIN", "E", "S");
    final String channel = "a".repeat(64);
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    JdbcTemplate sql;
    IndustryChannelOrderPolicyServiceImpl service;
    Owners owners;

    @BeforeEach
    void setup() throws Exception {
        var data =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:channel_policy_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(
                        new FileSystemResource(
                                "../shop-app/src/main/resources/db/migration/V607__industry_channel_order_admission.sql"))
                .execute(data);
        sql = new JdbcTemplate(data);
        sql.execute(
                "CREATE TABLE sys_idempotent(id BIGINT AUTO_INCREMENT PRIMARY KEY,idem_key"
                    + " VARCHAR(128),endpoint VARCHAR(128),user_no VARCHAR(64),request_hash"
                    + " CHAR(64),result_json LONGTEXT,created_at DATETIME,expire_at"
                    + " DATETIME,UNIQUE(idem_key,endpoint))");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChannelPolicyMapper.class);
        configuration.addMapper(SysIdempotentMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(data);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        owners = new Owners();
        service =
                new IndustryChannelOrderPolicyServiceImpl(
                        session.getMapper(ChannelPolicyMapper.class),
                        owners,
                        new IdempotencyService(session.getMapper(SysIdempotentMapper.class), json),
                        new DataSourceTransactionManager(data));
    }

    SaveReq request(long version, String currency, boolean enabled, long cap) {
        return new SaveReq(version, currency, enabled, true, false, cap, cap, 900);
    }

    @Test
    void configurationIsExplicitScopedAndCompletedReceiptsReplayWhileTheOwnerIsOffline() {
        assertThat(service.list(scope, 1, 20).records()).isEmpty();
        assertThatThrownBy(() -> service.get(scope, channel)).isInstanceOf(RuntimeException.class);
        var request = request(0, "AED", true, 1000);
        var created = service.save(scope, "A", channel, request, "create");
        assertThat(created.reservedMinor()).isZero();
        owners.unavailable = true;
        assertThat(service.save(scope, "A", channel, request, "create")).isEqualTo(created);
        assertThatThrownBy(() -> service.save(scope, "OTHER", channel, request, "create"))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        sql.queryForObject(
                                "SELECT MAX(LENGTH(endpoint)) FROM sys_idempotent", Integer.class))
                .isLessThanOrEqualTo(128);
    }

    @Test
    void editingLimitsOrDisablingPolicyCannotEraseOutstandingReceivableReservations() {
        service.save(scope, "A", channel, request(0, "AED", true, 1000), "create");
        sql.execute("UPDATE ord_channel_policy SET reserved_minor=100");
        var changed = service.save(scope, "A", channel, request(0, "AED", false, 50), "disable");
        assertThat(changed.enabled()).isFalse();
        assertThat(changed.reservedMinor()).isEqualTo(100);
        assertThat(changed.maxOutstandingMinor()).isEqualTo(50);
        assertThatThrownBy(
                        () ->
                                service.save(
                                        scope,
                                        "A",
                                        channel,
                                        request(1, "CNY", true, 1000),
                                        "currency"))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.get(scope, channel).currency()).isEqualTo("AED");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void anotherStoreOrActorCannotBorrowAVersionOrIdempotencyKey() {
        service.save(scope, "A", channel, request(0, "AED", true, 1000), "create");
        assertThatThrownBy(() -> service.get(new Scope("MAIN", "E", "OTHER"), channel))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                service.save(
                                        new Scope("MAIN", "E", "OTHER"),
                                        "A",
                                        channel,
                                        request(0, "AED", true, 1000),
                                        "create"))
                .isInstanceOf(RuntimeException.class);
        service.save(scope, "A", channel, request(0, "AED", false, 500), "update");
        assertThatThrownBy(
                        () ->
                                service.save(
                                        scope,
                                        "A",
                                        channel,
                                        request(0, "AED", true, 1000),
                                        "stale"))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.get(scope, channel).enabled()).isFalse();
    }

    @Test
    void policyJsonDoesNotCoerceBooleansNumbersVersionsOrUnknownCompletionFields() {
        String valid =
                "{\"version\":0,\"currency\":\"AED\",\"enabled\":true,\"allowPlatformCollected\":true,"
                    + "\"allowCashOnDelivery\":false,\"maxSingleMinor\":100,\"maxOutstandingMinor\":1000,\"holdSeconds\":900}";
        assertThat(json.readValue(valid, SaveReq.class).enabled()).isTrue();
        for (String body :
                Set.of(
                        valid.replace("\"enabled\":true", "\"enabled\":1"),
                        valid.replace("\"enabled\":true", "\"enabled\":\"true\""),
                        valid.replace("\"version\":0", "\"version\":0.1"),
                        valid.replace("\"holdSeconds\":900", "\"holdSeconds\":900.1"),
                        valid.replace("\"version\":0", "\"reservedMinor\":0,\"version\":0"))) {
            assertThatThrownBy(() -> json.readValue(body, SaveReq.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    static ErrorCode code(Runnable call) {
        try {
            call.run();
        } catch (BizException failed) {
            return failed.errorCode();
        }
        throw new AssertionError("expected BizException");
    }

    @Test
    void toggleChangesOnlyEnabledAndBumpsTheVersion() {
        var saved = service.save(scope, "A", channel, request(0, "AED", true, 1000), "create");
        sql.execute("UPDATE ord_channel_policy SET reserved_minor=100");
        var paused = service.setEnabled(scope, "M", channel, new EnabledReq(0L, false), "pause");
        assertThat(paused.enabled()).isFalse();
        assertThat(paused.version()).isEqualTo(1);
        assertThat(paused.reservedMinor()).isEqualTo(100);
        assertThat(paused)
                .usingRecursiveComparison()
                .ignoringFields("enabled", "version", "reservedMinor")
                .isEqualTo(saved);
        var resumed = service.setEnabled(scope, "M", channel, new EnabledReq(1L, true), "resume");
        assertThat(resumed.enabled()).isTrue();
        assertThat(resumed.version()).isEqualTo(2);
        assertThat(service.get(scope, channel)).isEqualTo(resumed);
    }

    @Test
    void toggleRejectsStaleVersionsAbsentPoliciesAndForeignStores() {
        assertThat(
                        code(
                                () ->
                                        service.setEnabled(
                                                scope,
                                                "M",
                                                channel,
                                                new EnabledReq(0L, false),
                                                "absent")))
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM ord_channel_policy", Integer.class))
                .isZero();
        service.save(scope, "A", channel, request(0, "AED", true, 1000), "create");
        service.setEnabled(scope, "M", channel, new EnabledReq(0L, false), "pause");
        assertThat(
                        code(
                                () ->
                                        service.setEnabled(
                                                scope,
                                                "M",
                                                channel,
                                                new EnabledReq(0L, true),
                                                "stale")))
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(
                        code(
                                () ->
                                        service.setEnabled(
                                                new Scope("MAIN", "E", "OTHER"),
                                                "M",
                                                channel,
                                                new EnabledReq(1L, true),
                                                "foreign")))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(service.get(scope, channel).enabled()).isFalse();
    }

    @Test
    void enablingAPolicyWithoutAnAllowedPaymentModeIsABadRequest() {
        service.save(
                scope,
                "A",
                channel,
                new SaveReq(0L, "AED", false, false, false, 1000L, 1000L, 900),
                "create");
        assertThat(
                        code(
                                () ->
                                        service.setEnabled(
                                                scope,
                                                "M",
                                                channel,
                                                new EnabledReq(0L, true),
                                                "enable")))
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(
                        code(
                                () ->
                                        service.setEnabled(
                                                scope, "M", channel, new EnabledReq(null, true), "n")))
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(
                        code(
                                () ->
                                        service.setEnabled(
                                                scope, "M", channel, new EnabledReq(0L, null), "n")))
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(service.get(scope, channel).enabled()).isFalse();
    }

    @Test
    void toggleReplaysByKeyAndKeepsItsOwnIdempotencyNamespace() {
        service.save(scope, "A", channel, request(0, "AED", true, 1000), "shared");
        var paused = service.setEnabled(scope, "M", channel, new EnabledReq(0L, false), "shared");
        assertThat(paused.version()).isEqualTo(1);
        owners.unavailable = true;
        assertThat(service.setEnabled(scope, "M", channel, new EnabledReq(0L, false), "shared"))
                .isEqualTo(paused);
        owners.unavailable = false;
        assertThatThrownBy(
                        () ->
                                service.setEnabled(
                                        scope, "OTHER", channel, new EnabledReq(0L, false), "shared"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                service.setEnabled(
                                        scope, "M", channel, new EnabledReq(0L, true), "shared"))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(DISTINCT endpoint) FROM sys_idempotent",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        sql.queryForObject(
                                "SELECT COUNT(*) FROM sys_idempotent WHERE endpoint LIKE"
                                        + " 'CHANNEL_ORDER_POLICY_ENABLED:%'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void toggleJsonAcceptsOnlyVersionAndEnabledWithoutCoercion() throws Exception {
        String valid = "{\"version\":3,\"enabled\":false}";
        var parsed = json.readValue(valid, EnabledReq.class);
        assertThat(parsed.version()).isEqualTo(3);
        assertThat(parsed.enabled()).isFalse();
        for (String body :
                Set.of(
                        "{\"version\":3,\"enabled\":0}",
                        "{\"version\":3,\"enabled\":\"false\"}",
                        "{\"version\":3.5,\"enabled\":false}",
                        "{\"version\":\"3\",\"enabled\":false}",
                        "{\"version\":3,\"enabled\":false,\"maxSingleMinor\":1}")) {
            assertThatThrownBy(() -> json.readValue(body, EnabledReq.class))
                    .as(body)
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
