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
                        new CreateType(type, "CN", new RedeemRate(2, 1), 2000, 365),
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
