package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.catalog.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

class CatalogPublicationMysqlTest {
    @Test
    void firstPackagePersistsAllPointersComponentsAndAudit() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            f.facade.saveSku(f.ops, f.sku());
            f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
            var fresh = new JdbcCatalogFixture(db);
            var view = fresh.facade.catalog(f.ops, "RETAIL");
            assertEquals(1, view.catalogVersion());
            assertEquals(3, view.plans().size());
            assertEquals(365, view.skus().getFirst().components().getFirst().periodDays());
            assertEquals(
                    "pro-1",
                    db.jdbc.queryForObject(
                            "SELECT current_version_no FROM sys_industry_plan WHERE"
                                    + " tenant_no='tenant-a' AND tier_code='PRO'",
                            String.class));
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM commerce_outbox WHERE"
                                    + " event_type='INDUSTRY_PUBLISHED'",
                            Integer.class));
        }
    }

    @Test
    void failureAfterGroupWritesRollsBackPointersVersionsAndAudit() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            f.facade.saveSku(f.ops, f.sku());
            int before =
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class);
            f.failAudit = true;
            assertThrows(
                    IllegalStateException.class,
                    () -> f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0)));
            assertEquals(0, f.facade.catalog(f.ops, "RETAIL").catalogVersion());
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sys_industry_plan_version WHERE"
                                    + " status='PUBLISHED'",
                            Integer.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mall_sku WHERE current_version_no IS NOT NULL",
                            Integer.class));
            assertEquals(
                    before,
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class));
        }
    }

    @Test
    void newPublicationStopsOldSkuAndRetainsQuoteOrderAndImmutableVersions() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            f.facade.saveSku(f.ops, f.sku());
            f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
            var original = f.repo.load("tenant-a", CatalogLockMode.NONE);
            db.jdbc.execute(Rows.mall_quote("tenant-a"));
            db.jdbc.update(
                    "UPDATE mall_quote SET line_snapshot=CAST(? AS JSON),status='LOCKED' WHERE"
                            + " tenant_no='tenant-a'",
                    "{\"skuVersionNo\":\"sku-1\",\"planVersionNo\":\"pro-1\",\"priceMinor\":9000}");
            db.jdbc.execute(Rows.mall_order_item("tenant-a"));
            db.jdbc.update(
                    "UPDATE mall_order_item SET sku_version_no='sku-1',sku_snapshot=CAST(? AS"
                            + " JSON)",
                    "{\"planVersionNo\":\"pro-1\",\"periodDays\":365}");
            var quote =
                    db.jdbc.queryForObject("SELECT line_snapshot FROM mall_quote", String.class);
            var order =
                    db.jdbc.queryForObject(
                            "SELECT sku_snapshot FROM mall_order_item", String.class);
            f.facade.savePlan(f.ops, f.plan("PRO", "pro-2", List.of("BASE", "POS")));
            f.facade.publishIndustry(
                    f.ops,
                    new IndustryPublicationCommand(
                            "second",
                            "RETAIL",
                            "free-1",
                            "pro-2",
                            "chain-1",
                            List.of(),
                            1,
                            f.planTokens("RETAIL"),
                            f.skuTokens("RETAIL"),
                            f.planDefinitionTokens("RETAIL"),
                            f.skuDefinitionTokens("RETAIL"),
                            "new policy"));
            var after = f.repo.load("tenant-a", CatalogLockMode.NONE);
            assertEquals(original.planVersions.get("pro-1"), after.planVersions.get("pro-1"));
            assertEquals(original.skuVersions.get("sku-1"), after.skuVersions.get("sku-1"));
            assertEquals("OFF_SALE", after.skus.get("sku").saleStatus());
            assertEquals(
                    quote,
                    db.jdbc.queryForObject("SELECT line_snapshot FROM mall_quote", String.class));
            assertEquals(
                    order,
                    db.jdbc.queryForObject(
                            "SELECT sku_snapshot FROM mall_order_item", String.class));
            assertThrows(
                    CatalogException.class,
                    () ->
                            f.facade.publishSku(
                                    f.ops,
                                    new SkuPublicationCommand(
                                            "reopen", "sku-1", 2, 3, "old version")));
        }
    }

    @Test
    void crossTenantReferencesAndUnownedWritesAreRejected() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            var other = new ActorContext("tenant-b", SubjectKind.OPERATOR, "operator-b", "trusted");
            assertThrows(
                    CatalogException.class,
                    () -> f.facade.publishIndustry(other, f.publication(List.of(), 0)));
            assertThrows(CatalogException.class, () -> f.facade.saveSku(other, f.sku()));
            assertTrue(f.repo.load("tenant-b", CatalogLockMode.NONE).planVersions.isEmpty());
            assertThrows(
                    IllegalStateException.class,
                    () -> f.repo.load("tenant-a", CatalogLockMode.EXCLUSIVE));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.repo.save(
                                    "tenant-a",
                                    new CatalogState(),
                                    new CatalogState(),
                                    new CatalogWriteContext(
                                            "operator-a", java.time.Instant.EPOCH)));
        }
    }

    @Test
    void concurrentPublishersWithSameExpectedVersionHaveSingleWinner() throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var tasks = new ArrayList<Future<Boolean>>();
                for (int i = 0; i < 2; i++)
                    tasks.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        try {
                                            f.facade.publishIndustry(
                                                    f.ops, f.publication(List.of(), 0));
                                            return true;
                                        } catch (CatalogException stale) {
                                            assertEquals(
                                                    CatalogErrorCode.VERSION_CONFLICT,
                                                    stale.code());
                                            return false;
                                        }
                                    }));
                start.countDown();
                int wins = 0;
                for (var task : tasks) if (task.get(20, TimeUnit.SECONDS)) wins++;
                assertEquals(1, wins);
                assertEquals(1, f.facade.catalog(f.ops, "RETAIL").catalogVersion());
                assertEquals(
                        1,
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM commerce_outbox WHERE"
                                        + " event_type='INDUSTRY_PUBLISHED'",
                                Integer.class));
            }
        }
    }

    @Test
    void differentIndustriesPublishConcurrentlyWithoutLostUpdates() throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.facade.saveBusinessType(
                    f.ops, new BusinessTypeDraft("FOOD", "餐饮", 2, false, 0, "create"));
            for (String code : List.of("FREE", "PRO", "CHAIN")) {
                var tier = PlanTier.valueOf(code);
                f.requestedPlanVersion = "food-" + code;
                f.facade.savePlan(
                        f.ops,
                        new PlanDraft(
                                "FOOD:" + code,
                                null,
                                "FOOD",
                                tier,
                                code,
                                "",
                                List.of(),
                                "policy",
                                0,
                                "draft"));
            }
            // FOOD gets its own base entry; no implicit live endpoint seed.
            f.accepted.add("FOOD_BASE");
            f.facade.saveBusinessEnd(
                    f.ops,
                    new BusinessEndDraft(
                            "FOOD_BASE",
                            "FOOD_BASE",
                            "食品基础",
                            EndScope.STORE,
                            List.of("FOOD"),
                            ReleaseStatus.VERIFIED,
                            false,
                            true,
                            true,
                            "client",
                            "routes",
                            "acceptance",
                            0,
                            "register"));
            for (String code : List.of("FREE", "PRO", "CHAIN"))
                f.facade.savePlan(
                        f.ops,
                        new PlanDraft(
                                "FOOD:" + code,
                                "food-" + code,
                                "FOOD",
                                PlanTier.valueOf(code),
                                code,
                                "",
                                List.of("FOOD_BASE"),
                                "policy",
                                1,
                                "draft"));
            var food =
                    new DefaultCatalogFacade(
                            f.repo,
                            f.uow,
                            (a, c) ->
                                    a.subjectKind() == SubjectKind.OPERATOR
                                            && "trusted".equals(a.accountSessionFingerprint())
                                            && f.capabilities.contains(c),
                            new ai.neargo.commerce.api.port.BusinessEndReadinessPort() {
                                public boolean verified(String t, BusinessEndDraft e) {
                                    return f.readiness.verified(t, e);
                                }

                                public Set<String> requiredEntitlementCodes(String t, String type) {
                                    return Set.of(type.equals("FOOD") ? "FOOD_BASE" : "BASE");
                                }
                            },
                            new JdbcCommerceEventPersistence(db.ds),
                            java.time.Clock.systemUTC(),
                            f.ids);
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return f.facade.publishIndustry(
                                            f.ops, f.publication(List.of(), 0));
                                });
                var b =
                        pool.submit(
                                () -> {
                                    start.await();
                                    return food.publishIndustry(
                                            f.ops,
                                            new IndustryPublicationCommand(
                                                    "food",
                                                    "FOOD",
                                                    "food-FREE",
                                                    "food-PRO",
                                                    "food-CHAIN",
                                                    List.of(),
                                                    0,
                                                    f.planTokens("FOOD"),
                                                    f.skuTokens("FOOD"),
                                                    f.planDefinitionTokens("FOOD"),
                                                    f.skuDefinitionTokens("FOOD"),
                                                    "publish"));
                                });
                start.countDown();
                assertEquals(1, a.get(20, TimeUnit.SECONDS).catalogVersion());
                assertEquals(1, b.get(20, TimeUnit.SECONDS).catalogVersion());
            }
            assertEquals(
                    6,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sys_industry_plan WHERE current_version_no IS NOT"
                                    + " NULL",
                            Integer.class));
        }
    }

    @Test
    void versionSnapshotsCanChangeTitleAndLimitWithoutRewritingHistory() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            f.facade.saveSku(f.ops, f.sku());
            f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
            var c =
                    f.component(
                            "component-2",
                            ComponentKind.PLAN,
                            "pro-1",
                            null,
                            null,
                            30,
                            1,
                            100,
                            false);
            f.requestedSkuVersion = "sku-2";
            var d =
                    new SkuDraft(
                            "product",
                            "sku",
                            null,
                            "新名称",
                            new Money("CNY", 2000),
                            new Money("CNY", 1800),
                            List.of("RETAIL"),
                            "{\"policy\":2}",
                            List.of(c),
                            2,
                            0,
                            "new term");
            f.facade.saveSku(f.ops, d);
            f.facade.publishSku(
                    f.ops, new SkuPublicationCommand("publish-2", "sku-2", 1, 2, "publish"));
            var versions = f.repo.load("tenant-a", CatalogLockMode.NONE).skuVersions;
            assertEquals("套餐", versions.get("sku-1").draft().title());
            assertEquals(1, versions.get("sku-1").draft().maxPerOrder());
            assertEquals("新名称", versions.get("sku-2").draft().title());
            assertEquals(2, versions.get("sku-2").draft().maxPerOrder());
            assertEquals(9000, versions.get("sku-1").draft().price().minor());
        }
    }

    @Test
    void lockingReadRejectsStalePublisherInsideOlderRepeatableReadSnapshot() throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThrows(
                        org.springframework.transaction.UnexpectedRollbackException.class,
                        () ->
                                f.uow.required(
                                        () -> {
                                            assertEquals(
                                                    0,
                                                    f.repo.load("tenant-a", CatalogLockMode.NONE)
                                                            .types
                                                            .get("RETAIL")
                                                            .catalogVersion());
                                            try {
                                                executor.submit(
                                                                () ->
                                                                        f.facade.publishIndustry(
                                                                                f.ops,
                                                                                f.publication(
                                                                                        List.of(),
                                                                                        0)))
                                                        .get(20, TimeUnit.SECONDS);
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                            assertThrows(
                                                    CatalogException.class,
                                                    () ->
                                                            f.facade.publishIndustry(
                                                                    f.ops,
                                                                    f.publication(List.of(), 0)));
                                            return null;
                                        }));
            }
            assertEquals(1, f.facade.catalog(f.ops, "RETAIL").catalogVersion());
        }
    }

    @Test
    void v4UpgradeRetainsChecksumsAndLeavesLegacyProofsAndSnapshotsNull() {
        try (var db = new MysqlDatabase()) {
            org.flywaydb.core.Flyway.configure()
                    .dataSource(db.ds)
                    .table("commerce_schema_history")
                    .locations("classpath:db/commerce/migration")
                    .target("4")
                    .load()
                    .migrate();
            var checksums =
                    db.jdbc.queryForList(
                            "SELECT checksum FROM commerce_schema_history WHERE version IN"
                                    + " ('1','2','3','4') ORDER BY installed_rank",
                            Integer.class);
            db.jdbc.execute(Rows.sys_business_type("legacy"));
            db.jdbc.update("UPDATE sys_business_type SET business_type_code='RETAIL'");
            db.jdbc.execute(Rows.sys_business_end("legacy"));
            db.jdbc.update(
                    "UPDATE sys_business_end SET"
                        + " scope='STORE',allowed_business_types='[\"RETAIL\"]',release_status='VERIFIED'");
            db.jdbc.execute(Rows.mall_product("legacy"));
            db.jdbc.update(
                    "UPDATE mall_product SET"
                        + " product_type='BUNDLE',allowed_business_types='[\"RETAIL\"]',sale_status='ON_SALE'");
            db.jdbc.execute(Rows.mall_sku("legacy"));
            db.jdbc.update(
                    "UPDATE mall_sku SET"
                            + " current_version_no='x',sale_status='ON_SALE',max_per_order=1");
            db.jdbc.execute(Rows.mall_sku_version("legacy"));
            db.jdbc.update(
                    "UPDATE mall_sku_version SET"
                        + " status='PUBLISHED',currency='CNY',allowed_business_types='[\"RETAIL\"]'");
            db.migrate();
            assertEquals(
                    checksums,
                    db.jdbc.queryForList(
                            "SELECT checksum FROM commerce_schema_history WHERE version IN"
                                    + " ('1','2','3','4') ORDER BY installed_rank",
                            Integer.class));
            var f = new JdbcCatalogFixture(db);
            var legacy = f.repo.load("legacy", CatalogLockMode.NONE);
            assertNull(legacy.ends.get("x").draft().clientId());
            assertNull(legacy.ends.get("x").draft().acceptanceRef());
            assertNull(legacy.skuVersions.get("x").draft().title());
            assertEquals(0, legacy.skuVersions.get("x").draft().maxPerOrder());
            var actor = new ActorContext("legacy", SubjectKind.OPERATOR, "ops", "trusted");
            var historical = f.facade.catalog(actor, "RETAIL");
            assertTrue(historical.skus().isEmpty());
            assertNull(historical.businessEnds().getFirst().draft().clientId());
            assertNull(historical.businessEnds().getFirst().draft().acceptanceRef());
            assertNull(historical.skuVersions().getFirst().draft().title());
            assertThrows(
                    CatalogException.class,
                    () ->
                            f.facade.publishSku(
                                    actor,
                                    new SkuPublicationCommand(
                                            "legacy", "x", 0, 0, "cannot newly sell")));
            f.setup();
            f.plans();
            db.jdbc.update(
                    "UPDATE sys_business_end SET client_id=NULL,acceptance_ref=NULL WHERE"
                            + " tenant_no='tenant-a' AND end_code='POS'");
            assertThrows(
                    CatalogException.class,
                    () -> f.facade.publishIndustry(f.ops, f.publication(List.of(), 0)));
            assertEquals(0, f.facade.catalog(f.ops, "RETAIL").catalogVersion());
        }
    }

    @Test
    void identicalBusinessNumbersAcrossEntityKindsDoNotCollideInAudit() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.accepted.add("RETAIL");
            f.facade.saveBusinessEnd(f.ops, f.endDraft("RETAIL", EndScope.STORE, false, true));
            f.category("category");
            f.facade.saveProduct(
                    f.ops,
                    new ProductDraft(
                            "RETAIL",
                            "category",
                            ProductKind.BUNDLE,
                            "same code",
                            "",
                            List.of("RETAIL"),
                            0,
                            0,
                            "same code"));
            assertEquals(
                    3,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(DISTINCT aggregate_type) FROM commerce_outbox WHERE"
                                    + " aggregate_no='RETAIL'",
                            Integer.class));
        }
    }

    @Test
    void publicFacadeCompletesDraftEditBootstrapNewVersionAndStopWithoutInternalReads() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            var api =
                    new DefaultCatalogFacade(
                            f.repo,
                            f.uow,
                            (a, c) ->
                                    a.subjectKind() == SubjectKind.OPERATOR
                                            && "trusted".equals(a.accountSessionFingerprint())
                                            && f.capabilities.contains(c),
                            f.readiness,
                            new JdbcCommerceEventPersistence(db.ds),
                            java.time.Clock.systemUTC(),
                            ns -> java.util.UUID.randomUUID().toString());
            api.saveBusinessType(
                    f.ops, new BusinessTypeDraft("RETAIL", "零售", 1, false, 0, "create"));
            api.saveBusinessEnd(f.ops, f.endDraft("BASE", EndScope.STORE, true, false));
            api.saveBusinessEnd(f.ops, f.endDraft("POS", EndScope.STORE, false, true));
            var ids = new EnumMap<PlanTier, String>(PlanTier.class);
            var initial = api.catalog(f.ops, "RETAIL");
            for (var definition : initial.planDefinitions()) {
                var codes =
                        definition.tier() == PlanTier.FREE
                                ? List.of("BASE")
                                : List.of("BASE", "POS");
                var saved =
                        api.savePlan(
                                f.ops,
                                new PlanDraft(
                                        definition.planNo(),
                                        null,
                                        "RETAIL",
                                        definition.tier(),
                                        definition.tier().name(),
                                        "draft",
                                        codes,
                                        "policy",
                                        0,
                                        "create draft"));
                var edited =
                        api.savePlan(
                                f.ops,
                                new PlanDraft(
                                        definition.planNo(),
                                        saved.draftVersionNo(),
                                        "RETAIL",
                                        definition.tier(),
                                        definition.tier().name(),
                                        "edited",
                                        codes,
                                        "policy",
                                        saved.version(),
                                        "edit draft"));
                ids.put(definition.tier(), edited.draftVersionNo());
            }
            api.saveCategory(
                    f.ops,
                    new CategoryDraft(
                            "subscriptions", null, Map.of("en", "Plans"), null, 0, true, 0,
                            "create category"));
            api.saveProduct(
                    f.ops,
                    new ProductDraft(
                            "product",
                            "subscriptions",
                            ProductKind.BUNDLE,
                            "订阅",
                            "",
                            List.of("RETAIL"),
                            0,
                            0,
                            "create product"));
            var component =
                    new SkuComponent(
                            null,
                            ComponentKind.PLAN,
                            ids.get(PlanTier.PRO),
                            null,
                            null,
                            365,
                            1,
                            100,
                            false,
                            "ON_FULFILLMENT");
            var sku =
                    api.saveSku(
                            f.ops,
                            new SkuDraft(
                                    "product",
                                    "sku",
                                    null,
                                    "第一版",
                                    new Money("CNY", 10000),
                                    new Money("CNY", 9000),
                                    List.of("RETAIL"),
                                    "{}",
                                    List.of(component),
                                    1,
                                    0,
                                    "new SKU"));
            var first =
                    api.publishIndustry(
                            f.ops,
                            new IndustryPublicationCommand(
                                    null,
                                    "RETAIL",
                                    ids.get(PlanTier.FREE),
                                    ids.get(PlanTier.PRO),
                                    ids.get(PlanTier.CHAIN),
                                    List.of(sku.draftVersionNo()),
                                    initial.catalogVersion(),
                                    f.planTokens("RETAIL"),
                                    f.skuTokens("RETAIL"),
                                    f.planDefinitionTokens("RETAIL"),
                                    f.skuDefinitionTokens("RETAIL"),
                                    "first publication"));
            var published = api.catalog(f.ops, "RETAIL");
            assertEquals(first.catalogVersion(), published.catalogVersion());
            assertEquals(4, first.publishedVersionNos().size());
            api.saveBusinessType(
                    f.ops,
                    new BusinessTypeDraft(
                            "RETAIL", "零售", 1, true, published.businessType().version(), "enable"));
            var pro =
                    published.planDefinitions().stream()
                            .filter(p -> p.tier() == PlanTier.PRO)
                            .findFirst()
                            .orElseThrow();
            var nextPlan =
                    api.savePlan(
                            f.ops,
                            new PlanDraft(
                                    pro.planNo(),
                                    null,
                                    "RETAIL",
                                    PlanTier.PRO,
                                    "专业新版",
                                    "",
                                    List.of("BASE", "POS"),
                                    "policy-2",
                                    0,
                                    "new Plan version"));
            var nextComponent =
                    new SkuComponent(
                            null,
                            ComponentKind.PLAN,
                            nextPlan.draftVersionNo(),
                            null,
                            null,
                            30,
                            1,
                            100,
                            false,
                            "AFTER_PREVIOUS");
            var nextSku =
                    api.saveSku(
                            f.ops,
                            new SkuDraft(
                                    "product",
                                    "sku",
                                    null,
                                    "第二版",
                                    new Money("CNY", 2000),
                                    new Money("CNY", 1800),
                                    List.of("RETAIL"),
                                    "{\"policy\":2}",
                                    List.of(nextComponent),
                                    2,
                                    0,
                                    "new SKU version"));
            api.publishIndustry(
                    f.ops,
                    new IndustryPublicationCommand(
                            null,
                            "RETAIL",
                            ids.get(PlanTier.FREE),
                            nextPlan.draftVersionNo(),
                            ids.get(PlanTier.CHAIN),
                            List.of(nextSku.draftVersionNo()),
                            published.catalogVersion(),
                            f.planTokens("RETAIL"),
                            f.skuTokens("RETAIL"),
                            f.planDefinitionTokens("RETAIL"),
                            f.skuDefinitionTokens("RETAIL"),
                            "second publication"));
            var current = api.catalog(f.ops, "RETAIL");
            assertEquals("第二版", current.skus().getFirst().title());
            assertEquals(
                    "第一版",
                    current.skuVersions().stream()
                            .filter(v -> v.draft().skuVersionNo().equals(sku.draftVersionNo()))
                            .findFirst()
                            .orElseThrow()
                            .draft()
                            .title());
            var sale = current.skuDefinitions().getFirst();
            api.stopSale(
                    f.ops,
                    new StopSaleCommand(
                            null, SaleTarget.SKU, sale.skuNo(), sale.version(), "normal stop"));
            var stopped = api.catalog(f.ops, "RETAIL");
            assertTrue(stopped.skus().isEmpty());
            assertEquals(2, stopped.skuVersions().size());
            var payload =
                    db.jdbc.queryForObject(
                            "SELECT payload FROM commerce_outbox WHERE tenant_no=? AND event_no=?",
                            String.class,
                            f.ops.tenantNo(),
                            first.publicationNo());
            for (String no : first.publishedVersionNos()) assertTrue(payload.contains(no));
        }
    }

    @Test
    void missingSkuSnapshotIsHistoricalOnlyAndCannotBeReactivatedByIndustryPackage() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            f.facade.saveSku(f.ops, f.sku());
            f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
            db.jdbc.update(
                    "UPDATE mall_sku_version SET sku_name_snapshot=NULL,max_per_order_snapshot=NULL"
                            + " WHERE tenant_no='tenant-a'");
            var view = f.facade.catalog(f.ops, "RETAIL");
            assertTrue(view.skus().isEmpty());
            assertNull(view.skuVersions().getFirst().draft().title());
            assertThrows(
                    CatalogException.class,
                    () ->
                            f.facade.publishIndustry(
                                    f.ops, f.publication(List.of("sku-1"), view.catalogVersion())));
            assertEquals(1, f.facade.catalog(f.ops, "RETAIL").catalogVersion());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void readCommittedCatalogReadAndPublicationCannotMixGenerations(boolean readOnly)
            throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            f.facade.saveSku(f.ops, f.sku());
            f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
            f.facade.savePlan(f.ops, f.plan("PRO", "pro-2", List.of("BASE", "POS")));
            f.facade.saveSku(
                    f.ops,
                    f.sku(
                            "sku-2",
                            List.of(
                                    f.component(
                                            "second",
                                            ComponentKind.PLAN,
                                            "pro-2",
                                            null,
                                            null,
                                            30,
                                            1,
                                            100,
                                            false))));
            var next =
                    new IndustryPublicationCommand(
                            "next",
                            "RETAIL",
                            "free-1",
                            "pro-2",
                            "chain-1",
                            List.of("sku-2"),
                            1,
                            f.planTokens("RETAIL"),
                            f.skuTokens("RETAIL"),
                            f.planDefinitionTokens("RETAIL"),
                            f.skuDefinitionTokens("RETAIL"),
                            "next generation");
            var paused = new PausingCatalogDataSource(db.ds);
            var manager =
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(paused);
            var reader =
                    new DefaultCatalogFacade(
                            new JdbcCatalogRepository(paused),
                            new JdbcCommerceUnitOfWork(paused, manager),
                            (a, c) ->
                                    a.subjectKind() == SubjectKind.OPERATOR
                                            && "trusted".equals(a.accountSessionFingerprint())
                                            && f.capabilities.contains(c),
                            f.readiness,
                            new JdbcCommerceEventPersistence(paused),
                            java.time.Clock.systemUTC(),
                            f.ids);
            var rc = new org.springframework.transaction.support.TransactionTemplate(manager);
            rc.setReadOnly(readOnly);
            rc.setIsolationLevel(
                    org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            try (var pool = Executors.newSingleThreadExecutor()) {
                var reading =
                        pool.submit(
                                () ->
                                        rc.execute(
                                                status -> {
                                                    assertEquals(
                                                            readOnly ? 1 : 0,
                                                            new org.springframework.jdbc.core
                                                                            .JdbcTemplate(paused)
                                                                    .queryForObject(
                                                                            "SELECT"
                                                                                + " @@session.transaction_read_only",
                                                                            Integer.class));
                                                    return reader.catalog(f.ops, "RETAIL");
                                                }));
                if (!paused.typeRead.await(10, TimeUnit.SECONDS)) {
                    reading.get(1, TimeUnit.SECONDS);
                    fail("reader did not reach first catalogue query");
                }
                try {
                    assertThrows(
                            org.springframework.dao.DataAccessException.class,
                            () ->
                                    f.uow.required(
                                            () -> {
                                                db.jdbc.execute(
                                                        "SET SESSION innodb_lock_wait_timeout=1");
                                                return f.facade.publishIndustry(f.ops, next);
                                            }));
                } finally {
                    paused.resume.countDown();
                }
                var original = reading.get(10, TimeUnit.SECONDS);
                assertEquals(1, original.catalogVersion());
                assertEquals(
                        "pro-1",
                        original.plans().stream()
                                .filter(p -> p.tier() == PlanTier.PRO)
                                .findFirst()
                                .orElseThrow()
                                .planVersionNo());
                assertEquals("sku-1", original.skus().getFirst().skuVersionNo());
            }
            f.facade.publishIndustry(f.ops, next);
            var current = f.facade.catalog(f.ops, "RETAIL");
            assertEquals(2, current.catalogVersion());
            assertEquals(
                    "pro-2",
                    current.plans().stream()
                            .filter(p -> p.tier() == PlanTier.PRO)
                            .findFirst()
                            .orElseThrow()
                            .planVersionNo());
            assertEquals("sku-2", current.skus().getFirst().skuVersionNo());
        }
    }

    @Test
    void trustedWriteContextPopulatesRowAuditAndPreservesPublishedHistoryAndBusinessRemark() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            String reason = "r".repeat(1024);
            f.facade.saveBusinessType(
                    f.ops, new BusinessTypeDraft("RETAIL", "零售", 1, false, 0, reason));
            f.end("BASE", EndScope.STORE, true, false);
            f.end("POS", EndScope.STORE, false, true);
            f.end("KDS", EndScope.STORE, false, true);
            f.plans();
            f.product();
            f.facade.saveSku(f.ops, f.sku());
            var firstAt = java.time.LocalDateTime.parse("2026-11-03T00:00:00");
            assertEquals(
                    "operator-a",
                    db.jdbc.queryForObject(
                            "SELECT created_by FROM sys_business_type WHERE tenant_no='tenant-a'",
                            String.class));
            assertEquals(
                    firstAt,
                    db.jdbc.queryForObject(
                            "SELECT created_at FROM sys_business_type WHERE tenant_no='tenant-a'",
                            java.time.LocalDateTime.class));
            assertNull(
                    db.jdbc.queryForObject(
                            "SELECT remark FROM sys_business_type WHERE tenant_no='tenant-a'",
                            String.class));
            db.jdbc.update(
                    "UPDATE sys_business_type SET remark='existing business note' WHERE"
                            + " tenant_no='tenant-a'");
            var second =
                    new ActorContext("tenant-a", SubjectKind.OPERATOR, "operator-b", "trusted");
            var at = java.time.Instant.parse("2026-11-04T01:02:03.123Z");
            var api =
                    new DefaultCatalogFacade(
                            f.repo,
                            f.uow,
                            (a, c) ->
                                    a.subjectKind() == SubjectKind.OPERATOR
                                            && "trusted".equals(a.accountSessionFingerprint())
                                            && f.capabilities.contains(c),
                            f.readiness,
                            new JdbcCommerceEventPersistence(db.ds),
                            java.time.Clock.fixed(at, java.time.ZoneOffset.UTC),
                            f.ids);
            var metadata = api.catalog(second, "RETAIL");
            api.saveBusinessType(
                    second,
                    new BusinessTypeDraft(
                            "RETAIL", "新零售", 2, false, metadata.businessType().version(), reason));
            var pro =
                    metadata.planVersions().stream()
                            .filter(p -> p.draft().tier() == PlanTier.PRO)
                            .findFirst()
                            .orElseThrow();
            api.savePlan(
                    second,
                    new PlanDraft(
                            pro.draft().planNo(),
                            pro.draft().planVersionNo(),
                            "RETAIL",
                            PlanTier.PRO,
                            "专业",
                            "",
                            List.of("POS", "BASE"),
                            "policy",
                            pro.version(),
                            "reorder draft ends"));
            var sku = metadata.skuVersions().getFirst();
            var component = sku.draft().components().getFirst();
            api.saveSku(
                    second,
                    new SkuDraft(
                            "product",
                            "sku",
                            sku.draft().skuVersionNo(),
                            "编辑规格",
                            sku.draft().listPrice(),
                            sku.draft().price(),
                            List.of("RETAIL"),
                            "{}",
                            List.of(
                                    new SkuComponent(
                                            component.componentNo(),
                                            component.kind(),
                                            component.planVersionNo(),
                                            null,
                                            null,
                                            30,
                                            1,
                                            100,
                                            false,
                                            "ON_FULFILLMENT")),
                            1,
                            sku.version(),
                            "edit same component"));
            api.publishIndustry(second, f.publication(List.of("sku-1"), 0));
            for (String table :
                    List.of("sys_business_type", "sys_industry_plan_version", "mall_sku_version")) {
                var row =
                        db.jdbc.queryForMap(
                                "SELECT created_by,updated_by,created_at,updated_at FROM "
                                        + table
                                        + " WHERE tenant_no='tenant-a'"
                                        + (table.equals("sys_industry_plan_version")
                                                ? " AND plan_version_no='pro-1'"
                                                : ""));
                assertEquals("operator-a", row.get("created_by"));
                assertEquals("operator-b", row.get("updated_by"));
                assertEquals(firstAt, (java.time.LocalDateTime) row.get("created_at"));
                assertEquals(
                        java.time.LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC),
                        (java.time.LocalDateTime) row.get("updated_at"));
            }
            for (String table : List.of("sys_plan_version_end", "mall_sku_component")) {
                String filter =
                        table.equals("sys_plan_version_end") ? " AND plan_version_no='pro-1'" : "";
                assertEquals(
                        0,
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM "
                                        + table
                                        + " WHERE tenant_no='tenant-a'"
                                        + filter
                                        + " AND (created_by IS NULL OR updated_by IS NULL OR"
                                        + " created_at IS NULL OR updated_at IS NULL OR"
                                        + " created_by<>'operator-a' OR updated_by<>'operator-b' OR"
                                        + " updated_at<>'2026-11-04 01:02:03.123')",
                                Integer.class));
            }
            assertEquals(
                    "existing business note",
                    db.jdbc.queryForObject(
                            "SELECT remark FROM sys_business_type WHERE tenant_no='tenant-a'",
                            String.class));
            assertEquals(
                    reason,
                    db.jdbc.queryForObject(
                            "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.reason')) FROM"
                                    + " commerce_outbox WHERE tenant_no='tenant-a' AND"
                                    + " event_type='TYPE_SAVED' ORDER BY id LIMIT 1",
                            String.class));
            var oldPlan =
                    db.jdbc.queryForMap(
                            "SELECT * FROM sys_industry_plan_version WHERE tenant_no='tenant-a' AND"
                                    + " plan_version_no='pro-1'");
            var oldSku =
                    db.jdbc.queryForMap(
                            "SELECT * FROM mall_sku_version WHERE tenant_no='tenant-a' AND"
                                    + " sku_version_no='sku-1'");
            f.requestedPlanVersion = "pro-2";
            var next =
                    api.savePlan(
                            second,
                            new PlanDraft(
                                    "RETAIL:PRO",
                                    null,
                                    "RETAIL",
                                    PlanTier.PRO,
                                    "新版",
                                    "",
                                    List.of("BASE", "POS"),
                                    "policy",
                                    0,
                                    "next version"));
            api.publishIndustry(
                    second,
                    new IndustryPublicationCommand(
                            null,
                            "RETAIL",
                            "free-1",
                            next.draftVersionNo(),
                            "chain-1",
                            List.of(),
                            1,
                            f.planTokens("RETAIL"),
                            f.skuTokens("RETAIL"),
                            f.planDefinitionTokens("RETAIL"),
                            f.skuDefinitionTokens("RETAIL"),
                            "replace"));
            assertEquals(
                    oldPlan,
                    db.jdbc.queryForMap(
                            "SELECT * FROM sys_industry_plan_version WHERE tenant_no='tenant-a' AND"
                                    + " plan_version_no='pro-1'"));
            assertEquals(
                    oldSku,
                    db.jdbc.queryForMap(
                            "SELECT * FROM mall_sku_version WHERE tenant_no='tenant-a' AND"
                                    + " sku_version_no='sku-1'"));
        }
    }

    @Test
    void twoDraftsPublishingAgainstSameSkuMasterHaveOneWinner() throws Exception {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            f.facade.publishIndustry(f.ops, f.publication(List.of(), 0));
            var first = f.facade.saveSku(f.ops, f.sku());
            var second =
                    f.facade.saveSku(
                            f.ops,
                            f.sku(
                                    "sku-2",
                                    List.of(
                                            f.component(
                                                    "second",
                                                    ComponentKind.PLAN,
                                                    "pro-1",
                                                    null,
                                                    null,
                                                    30,
                                                    1,
                                                    100,
                                                    false))));
            long observed = f.facade.catalog(f.ops, "RETAIL").skuDefinitions().getFirst().version();
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var tasks = new ArrayList<Future<String>>();
                for (var draft : List.of(first, second))
                    tasks.add(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        try {
                                            f.facade.publishSku(
                                                    f.ops,
                                                    new SkuPublicationCommand(
                                                            null,
                                                            draft.draftVersionNo(),
                                                            draft.version(),
                                                            observed,
                                                            "publish competing draft"));
                                            return draft.draftVersionNo();
                                        } catch (CatalogException stale) {
                                            assertEquals(
                                                    CatalogErrorCode.VERSION_CONFLICT,
                                                    stale.code());
                                            return null;
                                        }
                                    }));
                start.countDown();
                var winners = new ArrayList<String>();
                for (var task : tasks) {
                    var no = task.get(20, TimeUnit.SECONDS);
                    if (no != null) winners.add(no);
                }
                assertEquals(1, winners.size());
                var view = f.facade.catalog(f.ops, "RETAIL");
                assertEquals(winners.getFirst(), view.skus().getFirst().skuVersionNo());
                assertEquals(observed + 1, view.skuDefinitions().getFirst().version());
                assertEquals(
                        1,
                        view.skuVersions().stream()
                                .filter(v -> v.status().equals("PUBLISHED"))
                                .count());
                assertEquals(
                        1,
                        view.skuVersions().stream()
                                .filter(v -> v.status().equals("DRAFT"))
                                .count());
            }
            assertEquals(
                    1,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM commerce_outbox WHERE tenant_no='tenant-a' AND"
                                    + " event_type='SKU_PUBLISHED'",
                            Integer.class));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void staleSelectedPlanOrSkuContentTokenCannotPublishPartOfPackage(boolean editPlan) {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.product();
            var saved = f.facade.saveSku(f.ops, f.sku());
            var selected = f.publication(List.of(saved.draftVersionNo()), 0);
            var view = f.facade.catalog(f.ops, "RETAIL");
            if (editPlan) {
                var old =
                        view.planVersions().stream()
                                .filter(p -> p.draft().tier() == PlanTier.PRO)
                                .findFirst()
                                .orElseThrow();
                var d = old.draft();
                f.facade.savePlan(
                        f.ops,
                        new PlanDraft(
                                d.planNo(),
                                d.planVersionNo(),
                                d.typeCode(),
                                d.tier(),
                                d.displayName(),
                                d.summary(),
                                List.of("BASE", "POS", "KDS"),
                                d.policyVersion(),
                                old.version(),
                                "edit selected rights"));
            } else {
                var old = view.skuVersions().getFirst();
                var d = old.draft();
                f.facade.saveSku(
                        f.ops,
                        new SkuDraft(
                                d.productNo(),
                                d.skuNo(),
                                d.skuVersionNo(),
                                d.title(),
                                d.listPrice(),
                                new Money("CNY", 8000),
                                d.allowedBusinessTypes(),
                                d.refundPolicySnapshot(),
                                d.components(),
                                d.maxPerOrder(),
                                old.version(),
                                "edit selected price"));
            }
            int audits =
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class);
            assertEquals(
                    CatalogErrorCode.VERSION_CONFLICT,
                    assertThrows(
                                    CatalogException.class,
                                    () -> f.facade.publishIndustry(f.ops, selected))
                            .code());
            assertEquals(0, f.facade.catalog(f.ops, "RETAIL").catalogVersion());
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sys_industry_plan_version WHERE"
                                    + " status='PUBLISHED'",
                            Integer.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mall_sku_version WHERE status='PUBLISHED'",
                            Integer.class));
            assertEquals(
                    0,
                    db.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sys_industry_plan WHERE current_version_no IS NOT"
                                    + " NULL",
                            Integer.class));
            assertEquals(
                    audits,
                    db.jdbc.queryForObject("SELECT COUNT(*) FROM commerce_outbox", Integer.class));
        }
    }
}
