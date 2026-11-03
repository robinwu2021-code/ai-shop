package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.*;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.*;
import java.util.*;

class JdbcCatalogFixture {
    String requestedPlanVersion, requestedSkuVersion;
    int generatedComponent;
    final CommerceIdGenerator ids =
            namespace ->
                    switch (namespace) {
                        case "plan-version" -> requestedPlanVersion;
                        case "sku-version" -> requestedSkuVersion;
                        case "sku-component" -> "component-" + (++generatedComponent);
                        default -> java.util.UUID.randomUUID().toString();
                    };
    final ActorContext ops =
            new ActorContext("tenant-a", SubjectKind.OPERATOR, "operator-a", "trusted");
    final Set<String> capabilities =
            new HashSet<>(
                    List.of(
                            "commerce:catalog:read",
                            "commerce:catalog:edit",
                            "commerce:catalog:publish"));
    final Set<String> accepted = new HashSet<>(List.of("BASE", "POS", "KDS"));
    final JdbcCatalogRepository repo;
    final CommerceUnitOfWork uow;
    final CatalogFacade facade;
    boolean failAudit;

    JdbcCatalogFixture(MysqlDatabase db) {
        repo = new JdbcCatalogRepository(db.ds);
        uow = new JdbcCommerceUnitOfWork(db.ds, new DataSourceTransactionManager(db.ds));
        var eventDelegate = new JdbcCommerceEventPersistence(db.ds);
        var events =
                new CommerceEventPersistencePort() {
                    public boolean append(CommerceEvent e) {
                        boolean r = eventDelegate.append(e);
                        if (failAudit)
                            throw new IllegalStateException(
                                    "injected audit failure after repository save");
                        return r;
                    }

                    public boolean recordInbound(InboundFact f) {
                        return eventDelegate.recordInbound(f);
                    }

                    public EventPage pending(EventQuery q) {
                        return eventDelegate.pending(q);
                    }

                    public void markPublished(String t, String n, Instant at) {
                        eventDelegate.markPublished(t, n, at);
                    }
                };
        facade =
                new DefaultCatalogFacade(
                        repo,
                        uow,
                        (a, c) ->
                                capabilities.contains(c)
                                        && "trusted".equals(a.accountSessionFingerprint()),
                        readiness,
                        events,
                        Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC),
                        ids);
    }

    final BusinessEndReadinessPort readiness =
            new BusinessEndReadinessPort() {
                public boolean verified(String t, BusinessEndDraft e) {
                    return accepted.contains(e.entitlementCode())
                            && "client".equals(e.clientId())
                            && "routes".equals(e.routePolicyRef())
                            && "acceptance".equals(e.acceptanceRef());
                }

                public Set<String> requiredEntitlementCodes(String t, String type) {
                    return Set.of("BASE");
                }
            };

    void setup() {
        facade.saveBusinessType(ops, new BusinessTypeDraft("RETAIL", "零售", 1, false, 0, "create"));
        end("BASE", EndScope.STORE, true, false);
        end("POS", EndScope.STORE, false, true);
        end("KDS", EndScope.STORE, false, true);
    }

    BusinessEndDraft endDraft(String code, EndScope scope, boolean bootstrap, boolean sale) {
        return new BusinessEndDraft(
                code,
                code,
                code,
                scope,
                List.of("RETAIL"),
                ReleaseStatus.VERIFIED,
                sale,
                bootstrap,
                true,
                "client",
                "routes",
                "acceptance",
                0,
                "register");
    }

    void end(String code, EndScope scope, boolean bootstrap, boolean sale) {
        facade.saveBusinessEnd(ops, endDraft(code, scope, bootstrap, sale));
    }

    PlanDraft plan(String tier, String version, List<String> codes) {
        requestedPlanVersion = version;
        String target =
                repo.load("tenant-a", CatalogLockMode.NONE).planVersions.containsKey(version)
                        ? version
                        : null;
        return new PlanDraft(
                "RETAIL:" + tier,
                target,
                "RETAIL",
                PlanTier.valueOf(tier),
                tier,
                "summary",
                codes,
                "policy-1",
                0,
                "draft");
    }

    void plans() {
        facade.savePlan(ops, plan("FREE", "free-1", List.of("BASE")));
        facade.savePlan(ops, plan("PRO", "pro-1", List.of("BASE", "POS")));
        facade.savePlan(ops, plan("CHAIN", "chain-1", List.of("BASE", "POS", "KDS")));
    }

    void product() {
        facade.saveProduct(
                ops,
                new ProductDraft(
                        "product",
                        "subscriptions",
                        ProductKind.BUNDLE,
                        "套餐",
                        "description",
                        List.of("RETAIL"),
                        1,
                        0,
                        "draft"));
    }

    SkuComponent component(
            String no,
            ComponentKind kind,
            String plan,
            String end,
            String hardware,
            Integer days,
            int count,
            long weight,
            boolean gift) {
        return new SkuComponent(
                null, kind, plan, end, hardware, days, count, weight, gift, "ON_FULFILLMENT");
    }

    SkuDraft sku(String ver, List<SkuComponent> components) {
        requestedSkuVersion = ver;
        String target =
                repo.load("tenant-a", CatalogLockMode.NONE).skuVersions.containsKey(ver)
                        ? ver
                        : null;
        return new SkuDraft(
                "product",
                "sku",
                target,
                "套餐",
                new Money("CNY", 10000),
                new Money("CNY", 9000),
                List.of("RETAIL"),
                "{}",
                components,
                1,
                0,
                "draft");
    }

    SkuDraft sku() {
        return sku(
                "sku-1",
                List.of(
                        component(
                                "component-1",
                                ComponentKind.PLAN,
                                "pro-1",
                                null,
                                null,
                                365,
                                1,
                                100,
                                false)));
    }

    IndustryPublicationCommand publication(List<String> skus, long version) {
        return new IndustryPublicationCommand(
                "publication-" + version,
                "RETAIL",
                "free-1",
                "pro-1",
                "chain-1",
                skus,
                version,
                planTokens("RETAIL"),
                skuTokens("RETAIL"),
                "publish");
    }

    Map<String, Long> planTokens(String type) {
        return facade.catalog(ops, type).planVersions().stream()
                .collect(
                        java.util.stream.Collectors.toMap(
                                v -> v.draft().planVersionNo(), v -> v.version()));
    }

    Map<String, Long> skuTokens(String type) {
        return facade.catalog(ops, type).skuVersions().stream()
                .collect(
                        java.util.stream.Collectors.toMap(
                                v -> v.draft().skuVersionNo(), v -> v.version()));
    }
}
