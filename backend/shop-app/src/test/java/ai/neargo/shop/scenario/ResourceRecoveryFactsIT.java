package ai.neargo.shop.scenario;

import ai.neargo.shop.infra.ResourceOccupationNativeFixture;
import ai.neargo.shop.infra.ResourceOccupationFixtureConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.config.MybatisPlusConfig;
import ai.neargo.shop.merchant.service.ResourceCommandService;
import ai.neargo.shop.merchant.service.impl.ResourceCommandServiceImpl;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual member/role rows and the real journal; no source provider or capacity outcome is fabricated. */
class ResourceRecoveryFactsIT {
    private static final String ACCESS = "ai.neargo.shop.spi.user.MerchantOperatorAccessPort";
    private static final String FACTS = "ai.neargo.shop.spi.resource.ResourceCommandFactsPort";
    private JdbcTemplate sql;
    private String url;
    @Configuration(proxyBeanMethods = false)
    @MapperScan("ai.neargo.shop.merchant.mapper")
    static class Mappers { }
    private Class<?> required(String name) {
        return assertDoesNotThrow(() -> Class.forName(name));
    }
    @BeforeEach
    void database() throws Exception {
        required(ACCESS);
        required(FACTS);
        String admin = System.getProperty("resource-command.test.admin-url");
        String name = "resource_recovery_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(admin, "root", ""))
                .execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        url = admin.replace("/mysql?", "/" + name + "?");
        var source = new DriverManagerDataSource(url, "root", "");
        sql = new JdbcTemplate(source);
        sql.execute("CREATE TABLE mch_entity (tenant_no VARCHAR(32),entity_no VARCHAR(32),status VARCHAR(24),"
                + "archived_at DATETIME(3),deleted INT,PRIMARY KEY(tenant_no,entity_no))");
        sql.execute("CREATE TABLE mch_store (tenant_no VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),"
                + "status VARCHAR(24),deleted INT,PRIMARY KEY(tenant_no,entity_no,store_no))");
        sql.execute("CREATE TABLE mch_account (tenant_no VARCHAR(32),entity_no VARCHAR(32),"
                + "mch_account_no VARCHAR(32),user_no VARCHAR(64),is_owner INT,status VARCHAR(24),deleted INT)");
        sql.execute("CREATE TABLE mch_store_role (tenant_no VARCHAR(32),mch_account_no VARCHAR(32),"
                + "store_no VARCHAR(32),role VARCHAR(32),deleted INT)");
        sql.execute("CREATE TABLE mch_role (tenant_no VARCHAR(32),entity_no VARCHAR(32),role_code VARCHAR(32),"
                + "perms TEXT,deleted INT)");
        sql.execute("INSERT INTO mch_entity VALUES('MAIN','E','ACTIVE',NULL,0)");
        sql.execute("INSERT INTO mch_store VALUES('MAIN','E','S','ACTIVE',0),('MAIN','E','OTHER','ACTIVE',0)");
        sql.execute("INSERT INTO mch_account VALUES('MAIN','E','OWNER','UOWNER',1,'ACTIVE',0),"
                + "('MAIN','E','STAFF','USTAFF',0,'ACTIVE',0)");
        sql.execute("INSERT INTO mch_store_role VALUES('MAIN','STAFF','S','CUSTOM',0)");
        sql.execute("INSERT INTO mch_role VALUES('MAIN','E','CUSTOM','[\"biz:order:view\"]',0)");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V595__shared_resource_booking_foundation.sql"));
        }
        ResourceOccupationNativeFixture.install(sql.getDataSource());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("OWNER", "owner"), null, List.of()));
    }
    @AfterEach
    void clear() { DataScopeContext.clear(); SecurityContextHolder.clearContext(); }
    private ApplicationContextRunner runner() { return runner(Mappers.class); }
    private ApplicationContextRunner runner(Class<?> mapperConfiguration) {
        return new ApplicationContextRunner().withUserConfiguration(ResourceOccupationFixtureConfiguration.class, PrimaryPersistenceConfiguration.class,
                mapperConfiguration, ResourceCommandServiceImpl.class,
                required("ai.neargo.shop.merchant.port.MerchantOperatorAccessPortImpl"),
                required("ai.neargo.shop.merchant.port.ResourceCommandFactsPortImpl"))
                .withBean(AuditMetaObjectHandler.class).withPropertyValues("spring.datasource.url=" + url,
                        "spring.datasource.username=root", "spring.datasource.password=");
    }
    private Object query(String entity, String store, String operator, String kind, String permission) {
        return assertDoesNotThrow(() -> required(ACCESS + "$Query").getConstructors()[0].newInstance(
                "MAIN", entity, store, operator, "MERCHANT", kind, permission));
    }
    private Object invoke(ApplicationContext ctx, String port, String method, Object query) {
        try {
            return required(port).getMethod(method, query.getClass()).invoke(ctx.getBean(required(port)), query);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
    private boolean access(ApplicationContext ctx, String store, String operator, String kind, String permission) {
        return (Boolean) invoke(ctx, ACCESS, "allowed", query("E", store, operator, kind, permission));
    }
    private Object field(Object record, String name) {
        return assertDoesNotThrow(() -> record.getClass().getMethod(name).invoke(record));
    }
    private String plan() {
        var interval = new ResourcePlanContract.Interval(Instant.parse("2026-10-22T10:00:00Z"),
                Instant.parse("2026-10-22T11:00:00Z"));
        return " \n" + ResourcePlanContract.encode(List.of(new ResourcePlanContract.Demand("R", "I", 1,
                List.of(interval))), List.of(new ResourcePlanContract.Qualification("R", "I", null, null, false)))
                + "\n ";
    }
    private ResourceCommandService.Receipt accept(ApplicationContext ctx) {
        return ctx.getBean(ResourceCommandService.class).accept(new ResourceCommandService.Accept(
                new ResourceCommandService.Scope("MAIN", "E", "S"), ResourceCommandService.Action.RELEASE,
                "K", "BEAUTY_APPOINTMENT", "A", 0, 0, null, plan()));
    }
    private ResourceSourceAuthorityPort.Binding binding(ResourceCommandService.Receipt receipt, String hash) {
        return new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", receipt.commandNo(),
                "BEAUTY_APPOINTMENT", "A", 0, "RELEASE", hash, ResourcePlanContract.hash(receipt.frozenPayload()));
    }
    private Object lookup(ApplicationContext ctx, ResourceSourceAuthorityPort.Binding binding, String operator) {
        return invoke(ctx, FACTS, "lookup",
                new ResourceSourceAuthorityPort.Request(binding, operator, "MERCHANT", "MCH"));
    }
    @Test
    void actualHostMapperScanWiresBothOwnerReads() {
        runner(MybatisPlusConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(access(ctx, "S", "OWNER", "MCH", "biz:order:view")).isTrue();
            var receipt = accept(ctx);
            assertThat(field(lookup(ctx, binding(receipt, receipt.payloadHash()), "OWNER"), "command")).isNotNull();
        });
    }

    @Test
    void checksExactMemberNamespaceStoreAndLivePermissions() {
        runner().run(ctx -> {
            assertThat(access(ctx, "S", "OWNER", "MCH", "biz:store:admin")).isTrue();
            assertThat(access(ctx, "S", "UOWNER", "USR", "biz:store:admin")).isTrue();
            assertThat(access(ctx, "S", "OWNER", "USR", "biz:store:admin")).isFalse();
            assertThat(access(ctx, "S", "STAFF", "MCH", "biz:order:view")).isTrue();
            assertThat(access(ctx, "S", "USTAFF", "USR", "biz:order:view")).isTrue();
            assertThat(access(ctx, "OTHER", "STAFF", "MCH", "biz:order:view")).isFalse();
            assertThat(access(ctx, "S", "STAFF", "MCH", "biz:store:admin")).isFalse();
            sql.update("UPDATE mch_role SET perms='[]'");
            assertThat(access(ctx, "S", "STAFF", "MCH", "biz:order:view")).isFalse();
            sql.update("UPDATE mch_role SET perms='[\"biz:order:view\"]'");
            sql.update("UPDATE mch_store_role SET deleted=1");
            assertThat(access(ctx, "S", "STAFF", "MCH", "biz:order:view")).isFalse();
        });
    }
    @Test
    void deniesInactiveArchivedReadonlyDeletedAndAliasesEvenForOwner() {
        runner().run(ctx -> {
            assertThat(access(ctx, "s", "OWNER", "MCH", "biz:order:view")).isFalse();
            assertThat(access(ctx, "S", "owner", "MCH", "biz:order:view")).isFalse();
            sql.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no='OWNER'");
            assertThat(access(ctx, "S", "OWNER", "MCH", "biz:order:view")).isFalse();
            sql.update("UPDATE mch_account SET status='ACTIVE',deleted=1 WHERE mch_account_no='OWNER'");
            assertThat(access(ctx, "S", "OWNER", "MCH", "biz:order:view")).isFalse();
            sql.update("UPDATE mch_account SET deleted=0");
            sql.update("UPDATE mch_store SET status='READONLY'");
            assertThat(access(ctx, "S", "OWNER", "MCH", "biz:order:view")).isFalse();
            sql.update("UPDATE mch_store SET status='ACTIVE'");
            sql.update("UPDATE mch_entity SET archived_at=NOW(3)");
            assertThat(access(ctx, "S", "OWNER", "MCH", "biz:order:view")).isFalse();
        });
    }
    @Test
    void customOverrideAndAmbiguousMembershipFailClosed() {
        sql.update("INSERT INTO mch_role VALUES('MAIN','*','CUSTOM','[\"biz:store:admin\"]',0)");
        runner().run(ctx -> {
            assertThat(access(ctx, "S", "STAFF", "MCH", "biz:store:admin")).isFalse();
            sql.update("INSERT INTO mch_account VALUES('MAIN','E','SECOND','USTAFF',1,'ACTIVE',0)");
            assertThat(access(ctx, "S", "USTAFF", "USR", "biz:store:admin")).isFalse();
        });
    }
    @Test
    void readsOriginalAcceptedAndRejectedReceiptWithoutChangingJournal() {
        runner().run(ctx -> {
            var receipt = accept(ctx);
            var binding = binding(receipt, receipt.payloadHash());
            var pending = field(lookup(ctx, binding, "STAFF"), "command");
            assertThat(field(pending, "binding")).isEqualTo(binding);
            assertThat(field(pending, "status").toString()).isEqualTo("ACCEPTED");
            assertThat(field(pending, "terminalHash")).isNull();
            ctx.getBean(ResourceCommandService.class).rejectAfterRollback(receipt.scope(), receipt.commandNo(),
                    receipt.version(), "KNOWN_ABSENCE", " {\"reason\":\"no effects\"} ");
            var rejected = field(lookup(ctx, binding, "OWNER"), "command");
            assertThat(field(rejected, "status").toString()).isEqualTo("REJECTED");
            assertThat(field(rejected, "resultPayload")).isEqualTo(" {\"reason\":\"no effects\"} ");
            assertThat(field(rejected, "terminalHash").toString()).matches("[a-f0-9]{64}");
            assertThat(field(lookup(ctx, binding, "STAFF"), "command")).isEqualTo(rejected);
            assertThat(sql.queryForObject("SELECT version FROM mch_resource_command", Long.class)).isOne();
        });
    }
    @Test
    void readsOriginalSuccessAfterANewerCommandAndDistinguishesAbsence() {
        runner().run(ctx -> {
            var service = ctx.getBean(ResourceCommandService.class);
            var receipt = accept(ctx);
            var original = binding(receipt, receipt.payloadHash());
            var tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.execute(status -> service.completeSuccess(
                    service.lockForExecution(receipt.scope(), receipt.commandNo(), receipt.version()),
                    null, " {\"released\":true} "));
            var fact = field(lookup(ctx, original, "OWNER"), "command");
            assertThat(field(fact, "status").toString()).isEqualTo("SUCCEEDED");
            assertThat(field(fact, "resultPayload")).isEqualTo(" {\"released\":true} ");
            service.accept(new ResourceCommandService.Accept(receipt.scope(), ResourceCommandService.Action.RELEASE,
                    "K2", "BEAUTY_APPOINTMENT", "A", 1, 1, null, plan()));
            assertThat(field(lookup(ctx, original, "OWNER"), "command")).isEqualTo(fact);
            var missing = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "NEVER",
                    "BEAUTY_APPOINTMENT", "A", 0, "RELEASE", original.commandHash(), original.planHash());
            assertThat(field(lookup(ctx, missing, "OWNER"), "command")).isNull();
            var otherPlan = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", receipt.commandNo(),
                    "BEAUTY_APPOINTMENT", "A", 0, "RELEASE", original.commandHash(), "0".repeat(64));
            assertThatThrownBy(() -> lookup(ctx, otherPlan, "OWNER")).isInstanceOf(BizException.class);
        });
    }

    @Test
    void rejectsBindingMismatchRevokedPermissionAndCallerTransaction() {
        runner().run(ctx -> {
            var receipt = accept(ctx);
            var original = binding(receipt, receipt.payloadHash());
            assertThatThrownBy(() -> lookup(ctx, binding(receipt, "f".repeat(64)), "OWNER"))
                    .isInstanceOf(BizException.class);
            sql.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no='STAFF'");
            assertThatThrownBy(() -> lookup(ctx, original, "STAFF")).isInstanceOf(BizException.class);
            var tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> tx.execute(status -> lookup(ctx, original, "OWNER")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sql.queryForObject("SELECT version FROM mch_resource_command", Long.class)).isZero();
        });
    }
}
