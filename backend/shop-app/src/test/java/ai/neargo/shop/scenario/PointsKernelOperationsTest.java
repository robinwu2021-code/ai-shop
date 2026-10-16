package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PoolRequest.Action;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.pool.PointsSwitchService;
import ai.neargo.shop.pay.points.pool.TopupPlanService;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.query.PointsQueryService;
import ai.neargo.shop.pay.points.query.PointsStatsService;
import ai.neargo.shop.pay.points.recon.PointsReconService;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;
import ai.neargo.shop.spi.points.PointsEarnPort;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/** Synthetic parties, real points posting and SQL; no external channel is called. */
@SpringJUnitConfig(PointsPayoutPreparationTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
        properties = {
            "shop.points.enabled=true",
            "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_operations;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
            "spring.datasource.username=${points.jdbc.user:sa}",
            "spring.datasource.password=${points.jdbc.password:}",
            "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
        })
class PointsKernelOperationsTest {
    @Autowired ApplicationContext context;
    @Autowired PointsPostingService posting;

    @Autowired
    @Qualifier("payDataSource")
    DataSource dataSource;

    @BeforeAll
    void schema() {
        PointsPostingDatabaseTest.initializeSchema(dataSource);
    }

    @Test
    void recon_detects_a_missing_grant_action_even_when_money_and_account_totals_still_balance() {
        String type = fixture();
        var command = grant(type, "SOURCE", 20);
        context.getBean(EarnService.class).grant(command);
        var recon = context.getBean(PointsReconService.class);
        assertThat(recon.scan(type, 1000).balanced()).isTrue();
        jdbc().update(
                        "DELETE FROM pts_grant_position WHERE position_no=?",
                        PointsKeys.position("G", type, "SOURCE:" + type));
        var report = recon.scan(type, 1001);
        assertThat(report.balanced()).isFalse();
        assertThat(report.differences()).anyMatch(d -> d.code().equals("MISSING_GRANT_POSITION"));
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT c_minor FROM pts_fund_pool WHERE type_code=?",
                                        Long.class,
                                        type))
                .isEqualTo(20);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_grant_position WHERE type_code=?",
                                        Long.class,
                                        type))
                .isZero();
    }

    @Test
    void recon_detects_orphan_user_hold_without_repairing_or_releasing_it() {
        String type = fixture();
        posting.post(
                new Voucher(
                        "ORPHAN:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                move(type, HolderType.ISSUE, "PLATFORM", Bucket.ISSUABLE, -5),
                                move(type, HolderType.USER, "U", Bucket.HELD, 5)),
                        List.of(new FundMove(FundBucket.U, -5), new FundMove(FundBucket.C, 5)),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        var report = context.getBean(PointsReconService.class).scan(type, 1000);
        assertThat(report.differences()).anyMatch(d -> d.code().equals("ORPHAN_USER_HOLD"));
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT held_points FROM pts_account WHERE type_code=? AND"
                                                + " holder_type='USER'",
                                        Long.class,
                                        type))
                .isEqualTo(5);
    }

    @Test
    void
            stats_rebuild_includes_skipped_decisions_and_delivery_after_rebuild_does_not_double_count() {
        String type = fixture();
        var earning = context.getBean(EarnService.class);
        earning.grant(grant(type, "GRANTED", 20));
        context.getBean(PointsSwitchService.class)
                .change(
                        new PointsSwitchService.Command(
                                "OFF:" + type,
                                PointsSwitchService.Scope.TYPE,
                                type,
                                type,
                                false,
                                100,
                                "OP",
                                "TEST_PAUSE",
                                PointsSwitchService.Flow.EARN));
        earning.grant(grant(type, "SKIPPED", 20));
        var stats = context.getBean(PointsStatsService.class);
        var day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var snapshot = stats.rebuild(type, day);
        assertThat(snapshot.metrics())
                .containsEntry("grant_events", 2L)
                .containsEntry("granted_points", 20L)
                .containsEntry("expected_points", 40L)
                .containsEntry("skipped_events", 1L);
        assertThat(snapshot.skipReasons()).containsEntry("POINTS_DISABLED", 1L);
        assertThat(stats.rebuild(type, day).metrics()).isEqualTo(snapshot.metrics());
        assertThat(stats.project(type, 100)).isPositive();
        assertThat(stats.read(type, day, "TOTAL").metrics()).isEqualTo(snapshot.metrics());
        assertThat(stats.project(type, 100)).isZero();
    }

    @Test
    void plans_catch_up_in_bounded_batches_once_per_period_and_never_approve_or_invent_money() {
        String type = fixture();
        var plans = context.getBean(TopupPlanService.class);
        var first = LocalDate.of(2026, 10, 1);
        var plan =
                new TopupPlanService.Plan("PLAN:" + type, type, Action.TOPUP_U, 50, first, 1, true);
        plans.save("PLAN_SAVE:" + type, plan, "OP", "BACKING_PLAN");
        plans.save("PLAN_SAVE:" + type, plan, "OP", "BACKING_PLAN");
        assertThat(plans.tick(type, first.plusDays(2), 2)).isEqualTo(2);
        assertThat(plans.tick(type, first.plusDays(2), 2)).isEqualTo(1);
        assertThat(plans.tick(type, first.plusDays(2), 2)).isZero();
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_topup_plan WHERE type_code=?",
                                        Long.class,
                                        type))
                .isEqualTo(4);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_pool_request WHERE type_code=?",
                                        Long.class,
                                        type))
                .isZero();
        String request =
                plans.submitReceipt(
                        plan.planNo(), first, "DECLARED_RECEIPT", "MAKER", "RECEIPT_TO_REVIEW");
        assertThat(
                        plans.submitReceipt(
                                plan.planNo(),
                                first,
                                "DECLARED_RECEIPT",
                                "MAKER",
                                "RECEIPT_TO_REVIEW"))
                .isEqualTo(request);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT state FROM pts_pool_request WHERE request_no=?",
                                        String.class,
                                        request))
                .isEqualTo("SUBMITTED");
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT u_minor FROM pts_fund_pool WHERE type_code=?",
                                        Long.class,
                                        type))
                .isEqualTo(100);
        assertThatThrownBy(
                        () -> plans.submitReceipt(plan.planNo(), first, "OTHER", "MAKER", "CHANGE"))
                .hasMessage("POINTS_PLAN_RECEIPT_CONFLICT");
        plans.save(
                "PLAN_PAUSE:" + type,
                new TopupPlanService.Plan(plan.planNo(), type, Action.TOPUP_U, 50, first, 1, false),
                "OP",
                "PAUSE_FUTURE");
        assertThat(plans.tick(type, first.plusDays(3), 2)).isZero();
        plans.cancelPeriod(plan.planNo(), first.plusDays(1), "OP", "NO_LONGER_REQUIRED");
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT state FROM pts_topup_plan WHERE plan_no=? AND"
                                                + " period_key=?",
                                        String.class,
                                        plan.planNo(),
                                        first.plusDays(1).toString()))
                .isEqualTo("CANCELLED");
    }

    @Test
    void operational_pages_are_bounded_and_use_a_stable_id_cursor() {
        String type = fixture();
        context.getBean(EarnService.class).grant(grant(type, "PAGE", 20));
        var query = context.getBean(PointsQueryService.class);
        var first = query.page(type, Kind.VOUCHER, 0, 1);
        assertThat(first.rows()).hasSize(1);
        var next = query.page(type, Kind.VOUCHER, first.next(), 1);
        assertThat(next.rows()).hasSize(1);
        assertThat(next.next()).isGreaterThan(first.next());
        assertThat(query.page(type, Kind.VOUCHER, next.next(), 1).rows()).isEmpty();
        assertThatThrownBy(() -> query.page(type, Kind.VOUCHER, 0, 201))
                .hasMessage("POINTS_PAGE_INVALID");
    }

    private String fixture() {
        String type = "OP" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc().update(
                        "INSERT INTO"
                            + " pts_point_type(type_code,market,currency,rate_points,rate_minor,status,deduct_ratio_bp,idle_days)"
                            + " VALUES(?,'CN','CNY',1,1,'ACTIVE',10000,30)",
                        type);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')", type);
        var evidence = new EvidenceRef("OPS_FIXTURE:" + type, "CNY", 300);
        posting.recordEvidence(evidence);
        posting.post(
                new Voucher(
                        "OPS_FIXTURE:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                move(type, HolderType.MINT, "PLATFORM", Bucket.ISSUED, -100),
                                move(type, HolderType.ISSUE, "PLATFORM", Bucket.ISSUABLE, 100)),
                        List.of(new FundMove(FundBucket.U, 100), new FundMove(FundBucket.B, 200)),
                        List.of(),
                        0,
                        List.of(evidence),
                        "{}"));
        return type;
    }

    private PointsEarnPort.GrantCommand grant(String type, String source, long amount) {
        return new PointsEarnPort.GrantCommand(
                "EARN:" + source + ":" + type,
                source + ":" + type,
                "U",
                type,
                "STORE",
                "TEST",
                "ONLINE",
                1,
                List.of(
                        new PointsEarnPort.EarnLine(
                                "I",
                                "G",
                                "C",
                                1,
                                100,
                                0,
                                new PointsEarnPort.EarnRule(
                                        PointsEarnPort.RuleMode.FIXED, amount))),
                "M",
                "MINI");
    }

    private PointsMove move(String type, HolderType holder, String no, Bucket bucket, long points) {
        return new PointsMove(new AccountKey(type, holder, no, bucket), points);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }
}
