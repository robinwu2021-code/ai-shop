package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.admin.impl.PointsInspectionServiceImpl;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.type.PointTypeService.CreateType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

@SpringJUnitConfig(PointsOpsInspectionTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
        properties = {
            "shop.points.enabled=true",
            "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_ops_inspection;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
            "spring.datasource.username=${points.jdbc.user:sa}",
            "spring.datasource.password=${points.jdbc.password:}",
            "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
        })
class PointsOpsInspectionTest {
    @Autowired PointsInspectionService inspection;
    @Autowired PointsAdministrationService admin;
    @Autowired PointsPostingService posting;

    @Autowired
    @Qualifier("payDataSource")
    DataSource data;

    @BeforeAll
    void schema() {
        PointsPostingDatabaseTest.initializeSchema(data);
        // This isolated PAY slice must include the same named-type contract as the production mapper.
        try (var connection = data.getConnection();
                var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "pts_point_type", null)) {
            boolean named = false;
            while (columns.next()) named |= "name".equalsIgnoreCase(columns.getString("COLUMN_NAME"));
            if (!named) new ResourceDatabasePopulator(new ClassPathResource(
                    "db/migration/V537__points_type_name_unique.sql")).execute(data);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Points inspection schema fixture failed", failure);
        }
    }

    @Test
    void ledger_queries_keep_original_units_filter_before_limit_and_redact_result_payloads() {
        String type = fundedType();
        var account = inspection.account(type, HolderType.ISSUE, "PLATFORM");
        assertThat(account.exists()).isTrue();
        assertThat(account.snapshot().points(Bucket.ISSUABLE)).isEqualTo(200);
        assertThat(inspection.account(type, HolderType.STORE, "NO_STORE").exists()).isFalse();
        var entries = inspection.entries(type, HolderType.ISSUE, "PLATFORM", null, 0, 1);
        assertThat(entries.rows())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.holderType()).isEqualTo(HolderType.ISSUE);
                            assertThat(e.deltaPoints()).isEqualTo(200);
                        });
        var vouchers = inspection.vouchers(type, 0, 1);
        assertThat(vouchers.rows())
                .singleElement()
                .satisfies(
                        v -> {
                            assertThat(v.ratePoints()).isEqualTo(2);
                            assertThat(v.rateMinor()).isEqualTo(1);
                        });
        assertThat(new ObjectMapper().writeValueAsString(vouchers))
                .doesNotContain("secret_bank_payload", "resultJson", "payloadJson");
        assertThat(inspection.flows(type, 0, 1).rows())
                .singleElement()
                .satisfies(f -> assertThat(f.deltaMinor()).isEqualTo(100));
    }

    @Test
    void account_pages_filter_holder_type_and_continue_by_account_id() {
        String type = fundedType();
        JdbcTemplate jdbc = new JdbcTemplate(data);
        jdbc.update("INSERT INTO pts_account(type_code,holder_type,holder_no,available_points,last_active_at) VALUES(?,?,?,?,?)", type, "USER", "U_A", 17, 1234);
        jdbc.update("INSERT INTO pts_account(type_code,holder_type,holder_no,available_points,frozen,last_active_at) VALUES(?,?,?,?,?,?)", type, "USER", "U_B", 29, 1, 2345);
        jdbc.update("INSERT INTO pts_account(type_code,holder_type,holder_no,available_points) VALUES(?,?,?,?)", type, "STORE", "S_A", 41);

        var first = inspection.accounts(type, HolderType.USER, null, 0, 1);
        assertThat(first.rows()).hasSize(1);
        assertThat(first.rows().getFirst().snapshot().holderType()).isEqualTo(HolderType.USER);
        assertThat(first.rows().getFirst().snapshot().points(Bucket.AVAILABLE)).isEqualTo(17);
        assertThat(first.rows().getFirst().snapshot().lastActiveAt()).isEqualTo(1234);
        assertThat(first.hasMore()).isTrue();

        var second = inspection.accounts(type, HolderType.USER, null, first.next(), 1);
        assertThat(second.rows()).singleElement().satisfies(row -> {
            assertThat(row.snapshot().holderNo()).isEqualTo("U_B");
            assertThat(row.snapshot().frozen()).isTrue();
            assertThat(row.snapshot().points(Bucket.AVAILABLE)).isEqualTo(29);
        });
        assertThat(inspection.accounts(type, HolderType.STORE, null, 0, 20).rows())
                .singleElement().satisfies(row -> assertThat(row.snapshot().holderNo()).isEqualTo("S_A"));
    }

    @Test
    void transaction_queries_use_voucher_family_persisted_time_and_ledger_dimensions() {
        String type = fundedType();
        JdbcTemplate jdbc = new JdbcTemplate(data);
        LocalDateTime at = LocalDateTime.of(2026, 10, 20, 10, 0);
        jdbc.update("INSERT INTO pts_voucher(voucher_no,biz_ref,payload_hash,type_code,currency,rate_points,rate_minor,result_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "V_GRANT_" + type, "GRANT:SUB_1", "HASH", type, "CNY", 2, 1, "{}", at);
        jdbc.update("INSERT INTO pts_ledger_entry(entry_no,voucher_no,type_code,holder_type,holder_no,bucket,delta_points,after_points,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "E_" + type, "V_GRANT_" + type, type, "USER", "USER_1", "AVAILABLE", 20, 20, at);
        jdbc.update("INSERT INTO pts_voucher(voucher_no,biz_ref,payload_hash,type_code,currency,rate_points,rate_minor,result_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "V_ADJUST_" + type, "ADJUST:ADJ_1", "HASH2", type, "CNY", 2, 1, "{}", at);
        jdbc.update("INSERT INTO pts_voucher(voucher_no,biz_ref,payload_hash,type_code,currency,rate_points,rate_minor,result_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "V_HOLD_" + type, "HOLD:ORDER_1", "HASH3", type, "CNY", 2, 1, "{}", at);
        jdbc.update("INSERT INTO pts_voucher(voucher_no,biz_ref,payload_hash,type_code,currency,rate_points,rate_minor,result_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "V_CANCEL_" + type, "CANCEL:EVENT_1", "HASH4", type, "CNY", 2, 1, "{}", at);
        jdbc.update("INSERT INTO pts_voucher(voucher_no,biz_ref,payload_hash,type_code,currency,rate_points,rate_minor,result_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                "V_REFUND_" + type, "REFUND_CONFIRM:PREP_1", "HASH5", type, "CNY", 2, 1, "{}", at);

        var page = inspection.transactions(type, PointsInspectionService.TransactionDirection.EARN,
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), null, "USER_1", 0, 10);
        assertThat(page.rows()).singleElement().satisfies(row -> {
            assertThat(row.direction()).isEqualTo(PointsInspectionService.TransactionDirection.EARN);
            assertThat(row.voucher().bizRef()).isEqualTo("GRANT:SUB_1");
        });
        assertThat(inspection.transactions(type, PointsInspectionService.TransactionDirection.REDEEM,
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), null, null, 0, 10).rows())
                .singleElement().satisfies(row -> assertThat(row.voucher().bizRef()).isEqualTo("HOLD:ORDER_1"));
        assertThat(inspection.transactions(type, PointsInspectionService.TransactionDirection.REVERSAL,
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 20), null, null, 0, 10).rows())
                .extracting(row -> row.voucher().bizRef())
                .contains("CANCEL:EVENT_1", "REFUND_CONFIRM:PREP_1");
        var detail = inspection.transaction(type, "V_GRANT_" + type);
        assertThat(detail.entries().rows()).singleElement().satisfies(entry -> {
            assertThat(entry.holderNo()).isEqualTo("USER_1");
            assertThat(entry.deltaPoints()).isEqualTo(20);
        });
    }

    @Test
    void budget_request_list_exposes_persisted_submission_and_review_audit_fields() {
        String type = fundedType();
        String requestNo = "AUDIT:" + type;
        treasury.submit(new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Submission(
                "SUBMIT:" + type, requestNo, type,
                ai.neargo.shop.pay.points.model.PoolRequest.Action.TOPUP_U, 100,
                "EVIDENCE:" + type, null, "季度预算补充"), "MAKER");
        var before = treasury.requests(type, 0, 20).rows().stream()
                .filter(r -> r.requestNo().equals(requestNo)).findFirst().orElseThrow();
        assertThat(before.submittedBy()).isEqualTo("MAKER");
        assertThat(before.submittedAt()).isPositive();
        assertThat(before.submittedReason()).isEqualTo("季度预算补充");

        treasury.review(new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Review(
                "REVIEW:" + type, type, requestNo, before.version(), false, "凭证需补充"), "CHECKER");
        var after = treasury.requests(type, 0, 20).rows().stream()
                .filter(r -> r.requestNo().equals(requestNo)).findFirst().orElseThrow();
        assertThat(after.approvedBy()).isEqualTo("CHECKER");
        assertThat(after.reviewedAt()).isPositive();
        assertThat(after.reviewReason()).isEqualTo("凭证需补充");
    }

    @Autowired PointsPayoutPreparationTest.TestClock clock;

    @Test
    void freezing_is_audited_replayable_and_preserves_every_account_bucket() {
        String type = fundedType();
        posting.post(
                new Voucher(
                        "USER:" + type,
                        type,
                        "CNY",
                        new RedeemRate(2, 1),
                        List.of(
                                new PointsMove(
                                        new AccountKey(
                                                type,
                                                HolderType.ISSUE,
                                                "PLATFORM",
                                                Bucket.ISSUABLE),
                                        -20),
                                new PointsMove(
                                        new AccountKey(
                                                type, HolderType.USER, "USER", Bucket.AVAILABLE),
                                        20)),
                        List.of(new FundMove(FundBucket.U, -10), new FundMove(FundBucket.C, 10)),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        var before = inspection.account(type, HolderType.USER, "USER");
        var request =
                new PointsInspectionService.Freeze(
                        "FREEZE:" + type, type, "USER", before.version(), true, "TEST");
        clock.now = 1000;
        var first = inspection.freeze(request, "OPS");
        clock.now = 2000;
        assertThat(inspection.freeze(request, "OPS")).isEqualTo(first);
        var after = inspection.account(type, HolderType.USER, "USER");
        assertThat(after.snapshot().frozen()).isTrue();
        assertThat(after.snapshot().buckets()).isEqualTo(before.snapshot().buckets());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                inspection.freeze(
                                        new PointsInspectionService.Freeze(
                                                "STALE:" + type,
                                                type,
                                                "USER",
                                                before.version(),
                                                false,
                                                "TEST"),
                                        "OPS"))
                .hasMessageContaining("REVISION");
    }

    @Test
    void flows_preserve_reserved_and_locked_bucket_names() {
        String type = fundedType();
        var evidence = new EvidenceRef("BUFFER:" + type, "CNY", 100);
        posting.recordEvidence(evidence);
        posting.post(
                new Voucher(
                        "BUFFER:" + type,
                        type,
                        "CNY",
                        new RedeemRate(2, 1),
                        List.of(),
                        List.of(new FundMove(FundBucket.B, 100)),
                        List.of(),
                        0,
                        List.of(evidence),
                        "{}"));
        var target =
                new ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext(
                        ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPath.DIRECT,
                        "TEST",
                        "PLATFORM",
                        "PLATFORM",
                        "V1",
                        "SM",
                        null,
                        null,
                        0);
        var requests =
                new ai.neargo.shop.pay.points.model.PoolRequest.Action[] {
                    ai.neargo.shop.pay.points.model.PoolRequest.Action.WITHDRAW_U,
                    ai.neargo.shop.pay.points.model.PoolRequest.Action.WITHDRAW_B
                };
        for (var action : requests) {
            String no = action.name() + ":" + type;
            treasury.submit(
                    new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Submission(
                            "SUB:" + no, no, type, action, 20, null, target, "QA"),
                    "MAKER");
            treasury.review(
                    new ai.neargo.shop.pay.points.admin.PointsTreasuryService.Review(
                            "REVIEW:" + no, type, no, 0, true, "QA"),
                    "CHECKER");
        }
        var buckets = new java.util.ArrayList<PointsInspectionService.FlowBucket>();
        long after = 0;
        PointsInspectionService.FlowPage page;
        do {
            page = inspection.flows(type, after, 1);
            page.rows().forEach(r -> buckets.add(r.bucket()));
            after = page.next();
        } while (page.hasMore());
        assertThat(buckets)
                .contains(
                        PointsInspectionService.FlowBucket.LOCKED_U,
                        PointsInspectionService.FlowBucket.LOCKED_B);
    }

    @Autowired ai.neargo.shop.pay.points.admin.PointsTreasuryService treasury;

    @Test
    void queries_do_not_rebuild_statistics_or_reconciliation_and_refreshes_are_explicit() {
        String type = fundedType();
        clock.now = System.currentTimeMillis();
        var day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertThat(inspection.stats(type, day, PointsInspectionService.StatsScope.TOTAL, null))
                .isNull();
        assertThat(inspection.reconciliation(type, day)).isNull();
        inspection.rebuildStats(
                new PointsInspectionService.StatsRebuild("STATS:" + type, type, day, "TEST"),
                "OPS");
        assertThat(inspection.stats(type, day, PointsInspectionService.StatsScope.TOTAL, null))
                .isNotNull();
        var period = inspection.periodStats(type, day, day.plusDays(1));
        assertThat(period.availableDays()).isEqualTo(1);
        assertThat(period.missingDays()).isEqualTo(1);
        assertThat(period.metrics()).containsKeys("granted_points", "redeemed_points");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> inspection.periodStats(type, day, day.plusDays(93)))
                .hasMessageContaining("PERIOD_INVALID");
        var result =
                inspection.reconcile(
                        new PointsInspectionService.ReconRun("RECON:" + type, type, "TEST"), "OPS");
        var report = inspection.reconciliation(type, java.time.LocalDate.parse(result.reference()));
        assertThat(report.complete()).isTrue();
        assertThat(report.differences()).isEmpty();
    }

    private String fundedType() {
        String type = "OI_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        admin.create(
                new PointsAdministrationService.Create(
                        "CREATE:" + type,
                        new CreateType(type, type, "CNY", new RedeemRate(2, 1)),
                        "TEST"),
                "OPS");
        var evidence = new EvidenceRef("SYNTHETIC:" + type, "CNY", 100);
        posting.recordEvidence(evidence);
        posting.post(
                new Voucher(
                        "FUND:" + type,
                        type,
                        "CNY",
                        new RedeemRate(2, 1),
                        List.of(
                                new PointsMove(
                                        new AccountKey(
                                                type, HolderType.MINT, "PLATFORM", Bucket.ISSUED),
                                        -200),
                                new PointsMove(
                                        new AccountKey(
                                                type,
                                                HolderType.ISSUE,
                                                "PLATFORM",
                                                Bucket.ISSUABLE),
                                        200)),
                        List.of(new FundMove(FundBucket.U, 100)),
                        List.of(),
                        0,
                        List.of(evidence),
                        "{\"secret_bank_payload\":true}"));
        return type;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({PointsOpsConfigurationTest.Config.class, PointsInspectionServiceImpl.class})
    static class Config {}
}
