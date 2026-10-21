package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real isolated SQL validates version selection, immutable children and the shared product boundary. */
class BeautyCatalogIT {
    private BeautySchemaMigrationIT fixture;
    private JdbcTemplate sql;
    private final ObjectMapper json = new ObjectMapper();
    private boolean productAvailable = true;
    private String productEntity = "E";
    private int productDuration = 60;

    private Class<?> type(String name) {
        return assertDoesNotThrow(() -> Class.forName(name));
    }

    @BeforeEach
    void database() throws Exception {
        fixture = new BeautySchemaMigrationIT();
        fixture.isolatedDatabase();
        fixture.initialize("migrate");
        new ResourceDatabasePopulator(new ClassPathResource("platform-write-fixture.sql")).execute(fixture.source);
        sql = new JdbcTemplate(fixture.source);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACTOR", "operator"), null, List.of()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApplicationContextRunner runner() {
        Class port = type("ai.neargo.shop.spi.product.ServiceProductFactsPort");
        Object facts = Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port}, (proxy, method, args) -> {
            if (method.getName().equals("toString")) { return "product-authority-fixture"; }
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (!productAvailable) { throw new IllegalStateException("product unavailable"); }
            return port.getClasses()[0].getConstructors()[0].newInstance(productEntity, "S", "SKU", "G", 9900L,
                    productDuration);
        });
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                type("ai.neargo.shop.beauty.config.BeautyPersistenceConfiguration"),
                type("ai.neargo.shop.beauty.config.BeautyWriteGuardConfiguration"),
                type("ai.neargo.shop.beauty.config.BeautyPolicyConfiguration"),
                type("ai.neargo.shop.beauty.config.BeautyDefinitionConfiguration"),
                        BeautyPolicyPublicationIT.Routed.class)
                .withBean(ObjectMapper.class, () -> json).withBean(AuditMetaObjectHandler.class)
                .withBean("serviceProductFacts", port, () -> facts)
                .withPropertyValues("shop.beauty.enabled=true", "spring.datasource.url=" + fixture.source.getUrl(),
                        "spring.datasource.username=root", "spring.datasource.password=");
    }

    private Object call(ApplicationContext context, String service, String method, String dto, String body,
            String store, String key) {
        Class<?> contract = type("ai.neargo.shop.beauty.service." + service);
        Class<?> input = type("ai.neargo.shop.beauty.dto." + dto);
        Object request = json.readValue(body, input);
        try {
            if (key == null) {
                return contract.getMethod(method, String.class, String.class, String.class, input)
                        .invoke(context.getBean(contract), "E", store, "ACTOR", request);
            }
            return contract.getMethod(method, String.class, String.class, String.class, input, String.class)
                    .invoke(context.getBean(contract), "E", store, "ACTOR", request, key);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private Object policy(ApplicationContext context, String at, long revision, String key) {
        return call(context, "StorePolicyService", "publishPolicyVersion", "PublishStorePolicyVersionReq",
                "{\"expectedRevision\":" + revision + ",\"validFrom\":\"" + at + "\",\"policy\":{"
                        + "\"bookingAheadDays\":7,\"cancelRule\":{\"freeCancelBeforeMinutes\":60,"
                        + "\"allowCustomerCancel\":true},\"allowedModes\":[\"PREPAID_SINGLE\"]}}", "S", key);
    }

    private Object definition(ApplicationContext context, String key, long revision) {
        return call(context, "ServiceDefinitionService", "publishDefinitionVersion",
                "PublishServiceDefinitionVersionReq",
                "{\"skuNo\":\"SKU\",\"expectedRevision\":" + revision + ",\"duration\":{\"minutes\":60,"
                        + "\"bufferBeforeMin\":5,\"bufferAfterMin\":10},\"requirements\":[{\"roleCode\":\"TECH\","
                        + "\"resourceType\":\"STAFF\",\"skillGoodsNo\":\"G\",\"qty\":1,\"sequenceNo\":0,"
                        + "\"offsetMin\":0,\"durationMin\":60,\"selectionRule\":\"ANY_QUALIFIED\"}]}", "S", key);
    }

    @Test
    void selectsEffectivePolicyRatherThanLatestPublishedFuturePointer() {
        runner().run(context -> {
            Object first = policy(context, "2026-10-20T10:00:00Z", 0, "P1");
            policy(context, "2027-10-20T10:00:00Z", 1, "P2");
            String value = json.writeValueAsString(call(context, "StorePolicyService", "getPolicy", "StorePolicyQuery",
                    "{\"storeNo\":\"S\",\"at\":\"2026-10-20T18:00:00+08:00\"}", "S", null));
            assertThat(value).contains(json.readTree(json.writeValueAsString(first)).get("policyVersionNo").asText());
            assertThat(json.readTree(value).get("revision").asLong()).isEqualTo(2);
            assertThat(json.readTree(value).get("effectiveRevision").asLong()).isEqualTo(1);
        });
    }

    @Test
    void rejectsMissingEffectivePolicyAndQueryStoreSubstitution() {
        runner().run(context -> {
            policy(context, "2027-10-20T10:00:00Z", 0, "P1");
            assertThatThrownBy(() -> call(context, "StorePolicyService", "getPolicy", "StorePolicyQuery",
                    "{\"storeNo\":\"S\",\"at\":\"2026-10-20T10:00:00Z\"}", "S", null))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> call(context, "StorePolicyService", "getPolicy", "StorePolicyQuery",
                    "{\"storeNo\":\"OTHER\"}", "S", null)).isInstanceOf(BizException.class);
        });
    }

    @Test
    void publishesImmutableDefinitionChildrenAndDurableReplayEvenWhenProductUnavailable() {
        runner().run(context -> {
            Object first = definition(context, "D1", 0);
            productAvailable = false;
            assertThat(json.writeValueAsString(definition(context, "D1", 0))).isEqualTo(json.writeValueAsString(first));
            productAvailable = true;
            definition(context, "D2", 1);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_service_definition_version", Integer.class))
                    .isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_service_requirement", Integer.class)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_action_log", Integer.class)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> definition(context, "STALE", 1)).isInstanceOf(BizException.class);
        });
    }

    @Test
    void refusesForeignProductAndCallerInventedDurationBeforeAnyWrite() {
        runner().run(context -> {
            productEntity = "FOREIGN";
            assertThatThrownBy(() -> definition(context, "FOREIGN", 0)).isInstanceOf(BizException.class);
            productEntity = "E";
            productDuration = 90;
            assertThatThrownBy(() -> definition(context, "WRONG_DURATION", 0)).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_service_definition", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
        });
    }

    @Test
    void listsOnlyAuthorizedParentVersionsAndRejectsCrossStoreQuery() {
        runner().run(context -> {
            definition(context, "D1", 0);
            Object page = call(context, "ServiceDefinitionService", "listDefinitions", "ServiceDefinitionQuery",
                    "{\"storeNo\":\"S\",\"page\":1,\"size\":20}", "S", null);
            assertThat(json.readTree(json.writeValueAsString(page)).get("total").asLong()).isOne();
            Object empty = call(context, "ServiceDefinitionService", "listDefinitions", "ServiceDefinitionQuery",
                    "{\"storeNo\":\"OTHER\"}", "OTHER", null);
            assertThat(json.readTree(json.writeValueAsString(empty)).get("total").asLong()).isZero();
            assertThatThrownBy(() -> call(context, "ServiceDefinitionService", "listDefinitions",
                    "ServiceDefinitionQuery",
                    "{\"storeNo\":\"OTHER\"}", "S", null)).isInstanceOf(BizException.class);
        });
    }
    @Test
    void concurrentDefinitionFirstPublicationHasOneWinner() {
        runner().run(context -> {
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var futures = List.of("FIRST-A", "FIRST-B").stream().map(key -> workers.submit(() -> {
                    start.await();
                    try { definition(context, key, 0); return true; }
                    catch (BizException conflict) { return false; }
                })).toList();
                start.countDown();
                int winners = 0;
                for (var future : futures) { if (future.get(20, TimeUnit.SECONDS)) { winners++; } }
                assertThat(winners).isOne();
            }
            for (String table : List.of("bty_service_definition", "bty_service_definition_version",
                    "bty_service_requirement", "bty_action_log", "sys_idempotent", "sys_outbox")) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isOne();
            }
        });
    }

    @Test
    void definitionOutboxFailureAfterFlushRollsBackHeadChildrenAuditAndIdempotency() {
        runner().withInitializer(application -> application.getBeanFactory().addBeanPostProcessor(
                new BeanPostProcessor() {
                    @Override
                    public Object postProcessAfterInitialization(Object bean, String name) {
                        if (!name.equals("beautyOutboxPublisher")) { return bean; }
                        OutboxPublisher delegate = (OutboxPublisher) bean;
                        return (OutboxPublisher) (event, options) -> {
                            var receipt = delegate.publish(event, options);
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override
                                public void beforeCommit(boolean readOnly) {
                                    var transactionSql = new JdbcTemplate(application.getBean(DataSource.class));
                                    assertThat(transactionSql.queryForObject("SELECT COUNT(*) FROM sys_outbox",
                                            Integer.class)).isOne();
                                    throw new IllegalStateException("after-definition-outbox");
                                }
                            });
                            return receipt;
                        };
                    }
                })).run(context -> {
                    assertThatThrownBy(() -> definition(context, "ROLLBACK", 0))
                            .hasMessage("after-definition-outbox");
                    for (String table : List.of("bty_service_definition", "bty_service_definition_version",
                            "bty_service_requirement", "bty_action_log", "sys_idempotent", "sys_outbox")) {
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table,
                                Integer.class)).as(table).isZero();
                    }
                });
    }

    @Test
    void historicalDefinitionSelectsEarlierVersionThroughAuthorizedParent() {
        runner().run(context -> {
            Object first = definition(context, "D1", 0);
            definition(context, "D2", 1);
            sql.execute("UPDATE bty_service_definition SET created_at='2020-01-01 00:00:00'");
            sql.execute("UPDATE bty_service_definition_version SET created_at='2025-01-01 00:00:00' "
                    + "WHERE revision_index=1");
            sql.execute("UPDATE bty_service_definition_version SET created_at='2027-01-01 00:00:00' "
                    + "WHERE revision_index=2");
            Object result = call(context, "ServiceDefinitionService", "listDefinitions", "ServiceDefinitionQuery",
                    "{\"storeNo\":\"S\",\"at\":\"2026-01-01T00:00:00Z\"}", "S", null);
            var row = json.readTree(json.writeValueAsString(result)).get("records").get(0);
            assertThat(row.get("definitionVersionNo").asText())
                    .isEqualTo(json.readTree(json.writeValueAsString(first)).get("definitionVersionNo").asText());
            assertThat(row.get("revision").asLong()).isEqualTo(2);
            assertThat(row.get("effectiveRevision").asLong()).isEqualTo(1);
        });
    }

    @Test
    void rejectsAmbientWriteTransactionBeforeCallingAnExternalProductProvider() {
        runner().run(context -> {
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> tx.execute(status -> definition(context, "AMBIENT", 0)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside");
        });
    }

    @Test
    void historicalListBeforeFirstVersionReturnsAnEmptyPage() {
        runner().run(context -> {
            definition(context, "D1", 0);
            sql.execute("UPDATE bty_service_definition SET created_at='2020-01-01 00:00:00'");
            sql.execute("UPDATE bty_service_definition_version SET created_at='2025-01-01 00:00:00'");
            Object page = assertDoesNotThrow(() -> call(context, "ServiceDefinitionService", "listDefinitions",
                    "ServiceDefinitionQuery", "{\"storeNo\":\"S\",\"at\":\"2024-01-01T00:00:00Z\"}", "S", null));
            var result = json.readTree(json.writeValueAsString(page));
            assertThat(result.get("total").asLong()).isZero();
            assertThat(result.get("records").size()).isZero();
        });
    }

    @Test
    void publishesCustomerNotesAsImmutableVersionEvidenceAndRejectsChangedReplay() {
        runner().run(context -> {
            String command = "{\"skuNo\":\"SKU\",\"expectedRevision\":0,\"duration\":{\"minutes\":60,"
                    + "\"bufferBeforeMin\":5,\"bufferAfterMin\":10},\"requirements\":[{\"roleCode\":\"TECH\","
                    + "\"resourceType\":\"STAFF\",\"skillGoodsNo\":\"G\",\"qty\":1,\"sequenceNo\":0,"
                    + "\"offsetMin\":0,\"durationMin\":60,\"selectionRule\":\"ANY_QUALIFIED\"}],"
                    + "\"notes\":[\"请提前到店\"]}";
            Object result = call(context, "ServiceDefinitionService", "publishDefinitionVersion",
                    "PublishServiceDefinitionVersionReq", command, "S", "NOTES");
            assertThat(sql.queryForObject("SELECT notes FROM bty_service_definition_version", String.class))
                    .contains("请提前到店");
            assertThat(json.writeValueAsString(call(context, "ServiceDefinitionService", "publishDefinitionVersion",
                    "PublishServiceDefinitionVersionReq", command, "S", "NOTES")))
                    .isEqualTo(json.writeValueAsString(result));
            assertThatThrownBy(() -> call(context, "ServiceDefinitionService", "publishDefinitionVersion",
                    "PublishServiceDefinitionVersionReq", command.replace("请提前到店", "请勿迟到"), "S", "NOTES"))
                    .isInstanceOf(BizException.class);
            Object list = call(context, "ServiceDefinitionService", "listDefinitions", "ServiceDefinitionQuery",
                    "{\"storeNo\":\"S\",\"page\":1,\"size\":20}", "S", null);
            assertThat(json.writeValueAsString(list)).contains("请提前到店");
        });
    }
}
