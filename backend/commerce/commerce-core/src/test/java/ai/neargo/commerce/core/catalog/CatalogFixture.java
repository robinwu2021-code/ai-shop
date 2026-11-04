package ai.neargo.commerce.core.catalog;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.spi.*;

import java.time.*;
import java.util.*;

class CatalogFixture {
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
    final CatalogRepository repo =
            new CatalogRepository() {
                CatalogState state = new CatalogState();

                public CatalogState load(String t, CatalogLockMode mode) {
                    return state.copy();
                }

                public void save(
                        String t,
                        CatalogState before,
                        CatalogState s,
                        CatalogWriteContext context) {
                    state = s.copy();
                }
            };
    final CommerceUnitOfWork uow =
            new CommerceUnitOfWork() {
                public <T> T required(java.util.function.Supplier<T> w) {
                    return w.get();
                }
            };
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
    final List<CommerceEvent> audits = new ArrayList<>();
    final CommerceEventPersistencePort events =
            new CommerceEventPersistencePort() {
                public boolean append(CommerceEvent e) {
                    audits.add(e);
                    return true;
                }

                public boolean recordInbound(InboundFact f) {
                    throw new UnsupportedOperationException();
                }

                public EventPage pending(EventQuery q) {
                    throw new UnsupportedOperationException();
                }

                public void markPublished(String t, String no, Instant at) {
                    throw new UnsupportedOperationException();
                }
            };
    final CatalogFacade facade =
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
                planDefinitionTokens("RETAIL"),
                skuDefinitionTokens("RETAIL"),
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

    Map<String, Long> planDefinitionTokens(String type) {
        return facade.catalog(ops, type).planDefinitions().stream()
                .collect(java.util.stream.Collectors.toMap(v -> v.planNo(), v -> v.version()));
    }

    Map<String, Long> skuDefinitionTokens(String type) {
        return facade.catalog(ops, type).skuDefinitions().stream()
                .collect(java.util.stream.Collectors.toMap(v -> v.skuNo(), v -> v.version()));
    }
}
