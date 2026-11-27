package ai.neargo.commerce.starter;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.*;

class CommerceCatalogWiringTest {
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommerceCatalogAutoConfiguration.class));
    }

    @Test
    void defaultStartupDoesNotRequireCatalogPorts() {
        runner().run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(0, c.getBeansOfType(CatalogFacade.class).size());
                        });
    }

    @Test
    void optedInCatalogFailsWithoutTrustedPorts() {
        runner().withPropertyValues("commerce.enabled=true", "commerce.catalog.enabled=true")
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    private ApplicationContextRunner trustedCatalog() {
        return runner().withPropertyValues("commerce.enabled=true", "commerce.catalog.enabled=true")
                .withBean(
                        CatalogRepository.class,
                        () ->
                                new CatalogRepository() {
                                    public CatalogState load(String t, CatalogLockMode mode) {
                                        return new CatalogState();
                                    }

                                    public void save(
                                            String t,
                                            CatalogState b,
                                            CatalogState a,
                                            CatalogWriteContext context) {}
                                })
                .withBean(
                        CommerceUnitOfWork.class,
                        () ->
                                new CommerceUnitOfWork() {
                                    public <T> T required(java.util.function.Supplier<T> s) {
                                        return s.get();
                                    }
                                })
                .withBean(
                        CommerceIdGenerator.class,
                        () -> namespace -> java.util.UUID.randomUUID().toString())
                .withBean(CatalogAuthorizationPort.class, () -> (a, p) -> a.principalNo().equals("operator"))
                .withBean(
                        BusinessEndReadinessPort.class,
                        () ->
                                new BusinessEndReadinessPort() {
                                    public boolean verified(String t, BusinessEndDraft d) {
                                        return false;
                                    }

                                    public Set<String> requiredEntitlementCodes(
                                            String t, String c) {
                                        return Set.of();
                                    }
                                })
                .withBean(
                        CommerceEventPersistencePort.class,
                        () ->
                                new CommerceEventPersistencePort() {
                                    public boolean append(CommerceEvent e) {
                                        throw new UnsupportedOperationException();
                                    }

                                    public boolean recordInbound(InboundFact f) {
                                        throw new UnsupportedOperationException();
                                    }

                                    public EventPage pending(EventQuery q) {
                                        throw new UnsupportedOperationException();
                                    }

                                    public void markPublished(
                                            String t, String n, java.time.Instant i) {
                                        throw new UnsupportedOperationException();
                                    }
                                });
    }

    @Test
    void explicitTrustedWiringProducesTheSingleFacade() {
        trustedCatalog()
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(1, c.getBeansOfType(CatalogFacade.class).size());
                            assertInstanceOf(
                                    DefaultCatalogFacade.class, c.getBean(CatalogFacade.class));
                        });
    }

    @Test
    void configuredCatalogUsesTheMallForCategoriesAndTheFoundationForTypes() {
        try (var mall = new CommerceMigrationStartupTest.Database();
                var foundation = new CommerceMigrationStartupTest.Database()) {
            mall.jdbc.execute("""
                    CREATE TABLE mall_category (
                    id bigint primary key auto_increment,tenant_no varchar(32),category_code varchar(32),
                    parent_code varchar(32),`level` int,names json,icon varchar(512),sort_order int,
                    status varchar(16),version bigint,created_by varchar(64),updated_by varchar(64),
                    created_at datetime(3),updated_at datetime(3),deleted tinyint)
                    """);
            foundation.jdbc.execute("""
                    CREATE TABLE sys_dict_item (item_code varchar(64),name varchar(64),sort_no int,
                    status varchar(16),version bigint,attrs json,tenant_no varchar(32),
                    dict_type varchar(32),item_level varchar(16),deleted tinyint)
                    """);
            trustedCatalog().withPropertyValues("shop.modules.mall.datasource=testMall",
                            "shop.modules.foundation.datasource=testFoundation")
                    .withBean("testMallDataSource", javax.sql.DataSource.class, () -> mall.ds)
                    .withBean("testFoundationDataSource", javax.sql.DataSource.class, () -> foundation.ds)
                    .withBean("testMallTxManager", org.springframework.transaction.PlatformTransactionManager.class,
                            () -> new org.springframework.jdbc.datasource.DataSourceTransactionManager(mall.ds))
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var facade = context.getBean(CatalogFacade.class);
                        var actor = new ai.neargo.commerce.api.common.ActorContext("A",
                                ai.neargo.commerce.api.common.SubjectKind.OPERATOR, "operator", "session");
                        facade.saveCategory(actor, new CategoryDraft("cat", null, Map.of("en", "Cat"),
                                null, 0, true, 0, "create"));
                        assertEquals("ACTIVE", mall.jdbc.queryForObject("SELECT status FROM mall_category", String.class));
                        assertEquals("cat", facade.categories(actor).getFirst().categoryCode());
                        assertTrue(facade.businessTypes(actor).isEmpty());
                    });
        }
    }

    @Test
    void optedInBootUsesRealJdbcRepositoryAndTransactionalAudit() throws Exception {
        try (var db = new CommerceMigrationStartupTest.Database()) {
            var host = java.nio.file.Files.createTempDirectory("commerce-catalog-host");
            java.nio.file.Files.writeString(
                    host.resolve("V100__host.sql"), "CREATE TABLE host_marker(id INT);");
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    org.springframework.boot.flyway.autoconfigure
                                            .FlywayAutoConfiguration.class,
                                    CommercePersistenceAutoConfiguration.class,
                                    CommerceCatalogAutoConfiguration.class))
                    .withBean(javax.sql.DataSource.class, () -> db.ds)
                    .withBean(
                            org.springframework.transaction.PlatformTransactionManager.class,
                            () ->
                                    new org.springframework.jdbc.datasource
                                            .DataSourceTransactionManager(db.ds))
                    .withBean(
                            CommerceIdGenerator.class,
                            () -> namespace -> java.util.UUID.randomUUID().toString())
                    .withBean(
                            CatalogAuthorizationPort.class,
                            () ->
                                    (actor, capability) ->
                                            actor.subjectKind()
                                                            == ai.neargo.commerce.api.common
                                                                    .SubjectKind.OPERATOR
                                                    && "trusted-session"
                                                            .equals(
                                                                    actor
                                                                            .accountSessionFingerprint())
                                                    && Set.of(
                                                                    "commerce:catalog:read",
                                                                    "commerce:catalog:edit")
                                                            .contains(capability))
                    .withBean(
                            BusinessEndReadinessPort.class,
                            () ->
                                    new BusinessEndReadinessPort() {
                                        public boolean verified(
                                                String tenant, BusinessEndDraft registration) {
                                            return false;
                                        }

                                        public Set<String> requiredEntitlementCodes(
                                                String tenant, String type) {
                                            return Set.of();
                                        }
                                    })
                    .withPropertyValues(
                            "commerce.enabled=true",
                            "commerce.catalog.enabled=true",
                            "spring.flyway.locations=filesystem:" + host)
                    .run(
                            context -> {
                                assertNull(context.getStartupFailure());
                                assertInstanceOf(
                                        ai.neargo.commerce.persistence.JdbcCatalogRepository.class,
                                        context.getBean(CatalogRepository.class));
                                var api = context.getBean(CatalogFacade.class);
                                var actor =
                                        new ai.neargo.commerce.api.common.ActorContext(
                                                "catalog-tenant",
                                                ai.neargo.commerce.api.common.SubjectKind.OPERATOR,
                                                "operator",
                                                "trusted-session");
                                api.saveBusinessType(
                                        actor,
                                        new BusinessTypeDraft(
                                                "RETAIL",
                                                "零售",
                                                0,
                                                false,
                                                0,
                                                "create through Boot"));
                                assertEquals(
                                        ai.neargo.commerce.api.common.PlanTier.catalogTiers("RETAIL").size(),
                                        api.catalog(actor, "RETAIL").planDefinitions().size());
                                assertEquals(
                                        1,
                                        db.jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM commerce_outbox WHERE"
                                                        + " tenant_no='catalog-tenant' AND"
                                                        + " event_type='TYPE_SAVED'",
                                                Integer.class));
                            });
        }
    }
}
