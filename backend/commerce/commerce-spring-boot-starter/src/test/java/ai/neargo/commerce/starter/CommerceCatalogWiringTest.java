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

    @Test
    void explicitTrustedWiringProducesTheSingleFacade() {
        runner().withPropertyValues("commerce.enabled=true", "commerce.catalog.enabled=true")
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
                .withBean(CatalogAuthorizationPort.class, () -> (a, p) -> false)
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
                                })
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(1, c.getBeansOfType(CatalogFacade.class).size());
                            assertInstanceOf(
                                    DefaultCatalogFacade.class, c.getBean(CatalogFacade.class));
                        });
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
                                        3, api.catalog(actor, "RETAIL").planDefinitions().size());
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
