package ai.neargo.shop.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.member.service.MemberScopeRepository;
import ai.neargo.shop.member.service.impl.BrandAudienceSnapshotServiceImpl;
import ai.neargo.shop.member.port.BrandAudienceSnapshotPortImpl;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.Item;
import ai.neargo.shop.pay.entitlement.dto.ManagedTemplateView;
import ai.neargo.shop.payclient.EntitlementBatchAppService;
import ai.neargo.shop.payclient.EntitlementManagementAppService;
import ai.neargo.shop.promotion.cost.BrandPromotionCostService;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.merchantbridge.impl.BrandCouponApplicationServiceImpl;
import ai.neargo.shop.promotion.port.BrandCouponSourcePortImpl;
import ai.neargo.shop.promotion.service.BrandCouponService;
import ai.neargo.shop.promotion.service.impl.BrandCouponServiceImpl;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.AudienceSnapshot;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import ai.neargo.shop.spi.user.MemberIdentityFactsPort;
import ai.neargo.shop.spi.user.MemberIdentityFactsPort.Identity;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real Core transactions and production DDL; payment mocks simulate owner-applied RPC replies lost in transit. */
@EnabledIfEnvironmentVariable(named = "BRAND_AUDIENCE_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class BrandCouponApplicationMysqlTest {
    private static final ActivityActor ACTOR = new ActivityActor("MAIN", "E1", "A1", null, "M1", "B1");

    @Test
    void lostCreateReplyRecoversTheOriginalSourceAndGetResultNeverCallsPayment() throws Exception {
        try (var f = new Fixture()) {
            f.loseCreate = true;
            assertThatThrownBy(() -> f.app.execute(ACTOR, "saveCoupon", f.save(), "CREATE"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.nativeCreates).hasSize(1);
            clearInvocations(f.templates, f.batches);
            assertThat(f.app.getResult(ACTOR, "CREATE")).containsEntry("state", "PENDING");
            assertThat(f.app.commandAction(ACTOR, "CREATE")).isEqualTo("saveCoupon");
            verifyNoInteractions(f.templates, f.batches);
            var recovered = f.app.execute(ACTOR, "saveCoupon", f.save(), "CREATE");
            assertThat(recovered).containsEntry("state", "COMPLETED");
            assertThat(f.nativeCreates).hasSize(1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM pmt_brand_coupon_source", Long.class)).isEqualTo(1);
            var changed = new LinkedHashMap<>(f.save());
            changed.put("faceValueMinor", 999L);
            assertThatThrownBy(() -> f.app.execute(ACTOR, "saveCoupon", changed, "CREATE"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.app.execute(ACTOR, "previewAudience",
                    Map.of("id", recovered.get("id"), "audience", "ALL"), "CREATE"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.app.getResult(new ActivityActor("MAIN", "E1", "A2", null, "M1", "B1"),
                    "CREATE")).isInstanceOf(BizException.class);
        }
    }

    @Test
    void lostPublicationReplyKeepsOriginalVersionRuleAndCrossEntityScope() throws Exception {
        try (var f = new Fixture()) {
            String id = (String) f.app.execute(ACTOR, "saveCoupon", f.save(), "CREATE").get("id");
            var request = Map.<String, Object>of("id", id, "expectedRevision", 0L, "costRuleVersionNo", "R1");
            f.losePublish = true;
            assertThatThrownBy(() -> f.app.execute(ACTOR, "publishCoupon", request, "PUBLISH"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.core.get(ACTOR, id).status()).isEqualTo("READY");
            clearInvocations(f.templates, f.batches);
            assertThat(f.app.getResult(ACTOR, "PUBLISH")).containsEntry("state", "PENDING");
            verifyNoInteractions(f.templates, f.batches);
            var published = f.app.execute(ACTOR, "publishCoupon", request, "PUBLISH");
            String version = (String) published.get("nativeVersionNo");
            assertThat(f.nativePublications).hasSize(1);
            assertThat(f.core.get(ACTOR, id).costRuleVersionNo()).isEqualTo("R1");
            var definition = f.core.get(ACTOR, id).snapshot().definition();
            assertThat(f.port.permitsUse("MAIN", "E1", definition, version, "E2", "S2")).isTrue();
            assertThat(f.port.permitsUse("MAIN", "E1", definition, "COPIED_VERSION", "E2", "S2")).isFalse();
            assertThat(f.port.permitsUse("MAIN", "E1", definition, version, "E1", "S2")).isFalse();
            assertThatThrownBy(() -> f.port.validateTemplate("MAIN", "OTHER_ISSUER", definition))
                    .isInstanceOf(BizException.class);
            var forged = new EntitlementDefinition("forged", definition.kind(), definition.funder(),
                    definition.currency(), definition.benefitMode(), definition.benefitValue(),
                    definition.benefitCapMinor(), definition.benefitRef(), definition.minAmountMinor(),
                    definition.minQty(), definition.usageModel(), definition.totalUnits(), definition.dailyUnits(),
                    definition.validity(), definition.redemptionScope(), definition.allowedIssueModes());
            assertThatThrownBy(() -> f.port.validateTemplate("MAIN", "E1", forged)).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.app.execute(ACTOR, "publishCoupon",
                    Map.of("id", id, "expectedRevision", 1L, "costRuleVersionNo", "R2"), "OTHER_PUBLICATION"))
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    void lostIssueReplyNeverChangesAudienceAndRequiresTheExactClaimingNativeCommand() throws Exception {
        try (var f = new Fixture()) {
            String id = f.published();
            f.member("P1", "ACTIVE", "U1");
            f.member("LEAD", "LEAD", null);
            var preview = f.app.execute(ACTOR, "previewAudience", Map.of("id", id, "audience", "ALL"), "PREVIEW");
            assertThat(preview).containsEntry("matched", 2).containsEntry("reachable", 1).containsEntry("skipped", 1);
            String reference = (String) preview.get("previewToken");
            f.member("P2", "ACTIVE", "U2");
            var request = Map.<String, Object>of("id", id, "audience", "ALL", "audienceSnapshotNo", reference);
            f.loseIssue = true;
            assertThatThrownBy(() -> f.app.execute(ACTOR, "issueCoupon", request, "ISSUE"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.nativeAudiences.values().iterator().next().recipients())
                    .extracting(recipient -> recipient.userNo())
                    .containsExactly("U1");
            f.jdbc.update("UPDATE mbr_scope_member SET status='BLOCKED',version=1 WHERE customer_no='P1'");
            clearInvocations(f.templates, f.batches);
            assertThat(f.app.getResult(ACTOR, "ISSUE")).containsEntry("state", "PENDING");
            assertThat(f.app.issues(ACTOR)).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("state", "PENDING").containsEntry("currency", "CNY")
                        .containsEntry("currencyScale", 2).containsEntry("audienceLabel", "ALL")
                        .containsEntry("issued", null).containsEntry("skipped", null);
                assertThat(Instant.parse((String) row.get("createdAt"))).isBeforeOrEqualTo(Instant.now());
            });
            verifyNoInteractions(f.templates, f.batches);
            var recovered = f.app.execute(ACTOR, "issueCoupon", request, "ISSUE");
            assertThat(recovered).containsEntry("issued", 1).containsEntry("skipped", 1)
                    .containsEntry("plannedCount", 2).containsEntry("pending", 0).containsEntry("state", "COMPLETED");
            assertThat(f.nativeBatches).hasSize(1);
            var source = f.core.get(ACTOR, id);
            String command = f.core.result(ACTOR, "ISSUE").commandNo();
            assertThat(f.port.audience("MAIN", "E1", source.snapshot().definition(), source.nativeVersionNo(),
                    reference, "A1", command).recipients()).extracting(recipient -> recipient.userNo())
                    .containsExactly("U1");
            assertThatThrownBy(() -> f.port.audience("MAIN", "E1", source.snapshot().definition(),
                    source.nativeVersionNo(), reference, "A1", "OTHER_COMMAND")).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> f.app.execute(ACTOR, "issueCoupon", request, "NEW_ISSUE_KEY"))
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    void previewReceiptSurvivesLostResponseAndExpiryWithoutReselectingPeople() throws Exception {
        try (var f = new Fixture()) {
            String id = f.published();
            f.member("P1", "ACTIVE", "U1");
            var request = Map.<String, Object>of("id", id, "audience", "ALL");
            var original = f.app.execute(ACTOR, "previewAudience", request, "PREVIEW");
            f.jdbc.update("UPDATE mbr_brand_audience_snapshot SET expires_at=UTC_TIMESTAMP()-INTERVAL 11 MINUTE");
            f.member("P2", "ACTIVE", "U2");
            clearInvocations(f.templates, f.batches);
            assertThat(f.app.getResult(ACTOR, "PREVIEW")).isEqualTo(original);
            assertThat(f.app.execute(ACTOR, "previewAudience", request, "PREVIEW")).isEqualTo(original);
            assertThat(original).containsEntry("matched", 1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM mbr_brand_audience_snapshot", Long.class))
                    .isEqualTo(1);
            verifyNoInteractions(f.templates, f.batches);
        }
    }

    @Test
    void unavailableBrandFailsAsABusinessRejectionWithoutInventingScope() throws Exception {
        try (var f = new Fixture()) {
            String id = f.published();
            var definition = f.core.get(ACTOR, id).snapshot().definition();
            when(f.brands.find("MAIN", "M1", "B1", "E1")).thenReturn(null);
            assertThatThrownBy(() -> f.port.validateTemplate("MAIN", "E1", definition))
                    .isInstanceOf(BizException.class);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final String database = "brand_coupon_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final JdbcTemplate jdbc;
        private final EntitlementManagementAppService templates = mock(EntitlementManagementAppService.class);
        private final EntitlementBatchAppService batches = mock(EntitlementBatchAppService.class);
        private final BrandPromotionScopePort brands = mock(BrandPromotionScopePort.class);
        private final Map<String, ManagedTemplateView> nativeCreates = new HashMap<>();
        private final Map<String, PublishedVersionView> nativePublications = new HashMap<>();
        private final Map<String, BatchView> nativeBatches = new HashMap<>();
        private final Map<String, AudienceSnapshot> nativeAudiences = new HashMap<>();
        private final Map<String, Identity> identities = new HashMap<>();
        private final BrandCouponService core;
        private final BrandCouponSourcePortImpl port;
        private final BrandCouponApplicationService app;
        private boolean loseCreate;
        private boolean losePublish;
        private boolean loseIssue;

        private Fixture() throws Exception {
            String url = System.getenv("BRAND_AUDIENCE_MYSQL_URL");
            if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/(?:\\?.*)?")) {
                throw new IllegalArgumentException("Only the local 3308 server root URL is accepted");
            }
            String user = System.getenv("BRAND_AUDIENCE_MYSQL_USER");
            String password = System.getenv("BRAND_AUDIENCE_MYSQL_PASSWORD");
            admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            if (!admin.queryForObject("SELECT VERSION()", String.class).startsWith("9.")) {
                throw new IllegalStateException("MySQL 9 is required");
            }
            admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            var source = new DriverManagerDataSource(url.replace(":3308/", ":3308/" + database), user, password);
            jdbc = new JdbcTemplate(source);
            try {
                String ddl = Files.readString(Path.of("src/main/resources/db/migration/V652__brand_promotion.sql"));
                for (String table : List.of("mbr_brand_audience_snapshot", "pmt_brand_coupon_source",
                        "pmt_brand_coupon_command")) {
                    var statement = Pattern.compile("(?s)CREATE TABLE " + table + "\\s*\\(.*?;").matcher(ddl);
                    if (!statement.find()) throw new IllegalStateException("Production source DDL is missing");
                    jdbc.execute(statement.group());
                }
                jdbc.execute("CREATE TABLE mbr_scope_member (tenant_no VARCHAR(64),scope_member_no VARCHAR(64),"
                        + "scope_type VARCHAR(16),scope_no VARCHAR(64),customer_no VARCHAR(64),"
                        + "first_store_no VARCHAR(64),status VARCHAR(24),version BIGINT,deleted TINYINT,"
                        + "PRIMARY KEY(tenant_no,scope_member_no)) ENGINE=InnoDB");
                var json = new ObjectMapper();
                var db = new MemberScopeRepository(jdbc, new DataSourceTransactionManager(source), json);
                var stores = List.of(new BrandPromotionScopePort.Store("S1", "E1", 0, "BIND1", 0, "CN"),
                        new BrandPromotionScopePort.Store("S2", "E2", 0, "BIND2", 0, "CN"));
                when(brands.find("MAIN", "M1", "B1", "E1"))
                        .thenReturn(new BrandPromotionScopePort.Brand("M1", "B1", "E1", 0, 0, stores));
                when(brands.storeBelongsTo("MAIN", "S1", "E1")).thenReturn(true);
                when(brands.storeBelongsTo("MAIN", "S2", "E2")).thenReturn(true);
                var identityPort = mock(MemberIdentityFactsPort.class);
                when(identityPort.find(anyString(), anyString())).thenAnswer(call ->
                        Optional.ofNullable(identities.get(call.getArgument(1))));
                var users = mock(UserScopeFactsPort.class);
                when(users.findActive(anyString(), anyString())).thenAnswer(call ->
                        Optional.of(new UserScopeFactsPort.ActiveUser(call.getArgument(1), "Account")));
                var audienceService = new BrandAudienceSnapshotServiceImpl(db, identityPort, users, brands, json);
                var audiences = new BrandAudienceSnapshotPortImpl(audienceService);
                var costs = mock(BrandPromotionCostService.class);
                core = new BrandCouponServiceImpl(db, brands, audiences, costs, json);
                port = new BrandCouponSourcePortImpl(core, audiences, brands, json);
                app = new BrandCouponApplicationServiceImpl(core, audiences, costs, templates, batches);
                paymentTransport();
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        private void paymentTransport() {
            when(templates.create(any(), any(), anyString())).thenAnswer(call -> {
                unlocked();
                CatalogScope scope = call.getArgument(0);
                EntitlementDefinition definition = call.getArgument(1);
                String key = call.getArgument(2);
                port.validateTemplate(scope.tenantNo(), scope.entityNo(), definition);
                var result = nativeCreates.computeIfAbsent(key, ignored -> new ManagedTemplateView(
                        "NATIVE_" + key, "DRAFT", 0, null, definition, null));
                if (loseCreate) { loseCreate = false; throw new IllegalStateException("RPC reply lost"); }
                return result;
            });
            when(templates.policy(any(), anyString(), any(), anyString())).thenAnswer(call -> {
                unlocked();
                return null;
            });
            when(templates.publish(any(), anyString(), anyLong(), anyString())).thenAnswer(call -> {
                unlocked();
                String template = call.getArgument(1);
                String key = call.getArgument(3);
                var definition = nativeCreates.values().stream().filter(value -> value.templateNo().equals(template))
                        .findFirst().orElseThrow().draft();
                port.validateTemplate("MAIN", "E1", definition);
                var result = nativePublications.computeIfAbsent(key, ignored -> new PublishedVersionView(
                        "VERSION_" + key, template, 1, definition, "hash", Instant.now()));
                if (losePublish) { losePublish = false; throw new IllegalStateException("RPC reply lost"); }
                return result;
            });
            when(batches.create(any(), any(), anyString())).thenAnswer(call -> {
                unlocked();
                CatalogScope scope = call.getArgument(0);
                CreateBatch request = call.getArgument(1);
                String key = call.getArgument(2);
                if (nativeBatches.containsKey(key)) return nativeBatches.get(key);
                var published = nativePublications.values().stream()
                        .filter(value -> value.versionNo().equals(request.templateVersionNo()))
                        .findFirst().orElseThrow();
                var audience = port.audience(scope.tenantNo(), scope.entityNo(), published.definition(),
                        request.templateVersionNo(), request.audienceFactRef(), scope.actorNo(), key);
                nativeAudiences.put(key, audience);
                var result = new BatchView("BATCH_" + key, "PROCESSING", audience.recipients().size(), 0, 0, 0,
                        audience.recipients().size(), List.of());
                nativeBatches.put(key, result);
                if (loseIssue) { loseIssue = false; throw new IllegalStateException("RPC reply lost"); }
                return result;
            });
            when(batches.resume(any(), anyString(), anyInt())).thenAnswer(call -> {
                unlocked();
                String no = call.getArgument(1);
                String key = nativeBatches.entrySet().stream().filter(entry -> entry.getValue().batchNo().equals(no))
                        .findFirst().orElseThrow().getKey();
                var recipients = nativeAudiences.get(key).recipients();
                var items = recipients.stream().map(recipient -> new Item(recipient.userNo(),
                        recipient.eligible() ? "GRANT_" + recipient.userNo() : null,
                        recipient.eligible() ? "ISSUED" : "SKIPPED", recipient.reasonCode())).toList();
                int issued = (int) recipients.stream().filter(recipient -> recipient.eligible()).count();
                var result = new BatchView(no, "COMPLETED", recipients.size(), issued, recipients.size() - issued,
                        0, 0, items);
                nativeBatches.put(key, result);
                return result;
            });
        }

        private String published() {
            String id = (String) app.execute(ACTOR, "saveCoupon", save(), "CREATE").get("id");
            app.execute(ACTOR, "publishCoupon", Map.of("id", id, "expectedRevision", 0L,
                    "costRuleVersionNo", "R1"), "PUBLISH");
            return id;
        }

        private Map<String, Object> save() {
            return Map.ofEntries(Map.entry("name", "品牌减免券"), Map.entry("type", "cashCoupon"),
                    Map.entry("status", "draft"), Map.entry("storeIds", List.of("S1", "S2")),
                    Map.entry("currency", "CNY"), Map.entry("currencyScale", 2), Map.entry("faceValueMinor", 500L),
                    Map.entry("thresholdMinor", 1000L), Map.entry("validDays", 30), Map.entry("limit", 100L),
                    Map.entry("perPerson", 1));
        }

        private void member(String customer, String status, String user) {
            jdbc.update("INSERT INTO mbr_scope_member VALUES('MAIN',?,'BRAND','B1',?,'S2',?,0,0)",
                    "MEMBER_" + customer, customer, status);
            identities.put(customer, new Identity(customer, user, null, true));
        }

        private void unlocked() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE " + database);
        }
    }
}
