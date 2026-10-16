package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.pay.market.MarketService;
import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.pay.points.app.PointsSettlementHook;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutCommand;
import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.pay.points.event.PointsEventWorker;
import ai.neargo.shop.pay.points.event.impl.PointsFactPortImpl;
import ai.neargo.shop.pay.points.job.PointsJobServices;
import ai.neargo.shop.pay.points.job.PointsRecoveryService;
import ai.neargo.shop.pay.points.lifecycle.TypeRetirementService;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService;
import ai.neargo.shop.pay.points.payout.PointsPayoutService;
import ai.neargo.shop.pay.points.payout.StatementPayoutService;
import ai.neargo.shop.pay.points.pool.PointsSwitchService;
import ai.neargo.shop.pay.points.pool.PoolRequestService;
import ai.neargo.shop.pay.points.pool.TopupPlanService;
import ai.neargo.shop.pay.points.pool.impl.PoolRequestServiceImpl;
import ai.neargo.shop.pay.points.pool.impl.PoolServiceImpl;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPath;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.port.PointsIncomeAccountProvider;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.query.PointsStatsService;
import ai.neargo.shop.pay.points.recon.PointsReconService;
import ai.neargo.shop.pay.points.redeem.RedeemService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.refund.PointsRefundPreparationService;
import ai.neargo.shop.pay.points.statement.StoreStatementService;
import ai.neargo.shop.pay.points.statement.StoreStatementService.Period;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.type.PointTypeService;
import ai.neargo.shop.pay.points.type.impl.PointTypeServiceImpl;
import ai.neargo.shop.spi.points.PointsEarnPort;
import ai.neargo.shop.spi.points.PointsFactPort;
import ai.neargo.shop.spi.points.PointsFactPort.FactKind;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import ai.neargo.shop.spi.points.PointsFactPort.LineSnapshot;
import ai.neargo.shop.spi.points.PointsFactPort.RefundClosureFact;
import ai.neargo.shop.spi.points.PointsFactPort.RefundConfirmationFact;
import ai.neargo.shop.spi.points.PointsRedeemPort;
import ai.neargo.shop.spi.points.PointsRefundPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * Entire points kernel, synthetic stores and explicitly simulated bank. No order producer is
 * installed.
 */
@SpringJUnitConfig(PointsCoreAcceptanceTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
        properties = {
            "shop.points.enabled=true",
            "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_core;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
            "spring.datasource.username=${points.jdbc.user:sa}",
            "spring.datasource.password=${points.jdbc.password:}",
            "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
        })
class PointsCoreAcceptanceTest {
    @Autowired ApplicationContext context;

    @Autowired
    @Qualifier("payDataSource")
    DataSource dataSource;

    @Autowired PointsPostingService posting;

    @Autowired
    @Qualifier("payTxManager")
    PlatformTransactionManager txManager;

    @Autowired PointsReadStore read;
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> activeTypes = new ArrayList<>();

    @BeforeAll
    void schema() {
        PointsPostingDatabaseTest.initializeSchema(dataSource);
        jdbc().execute(
                        "CREATE TABLE IF NOT EXISTS stl_payment(payment_no VARCHAR(64) PRIMARY"
                            + " KEY,direction VARCHAR(24),after_sale_no VARCHAR(40),deleted TINYINT"
                            + " DEFAULT 0)");
    }

    @AfterEach
    void releaseMarketClaim() {
        activeTypes.forEach(
                type ->
                        jdbc().update(
                                        "UPDATE pts_point_type SET active_market=NULL WHERE"
                                            + " type_code=?",
                                        type));
        activeTypes.clear();
    }

    @Test
    void
            full_store_chain_refunds_offsets_reuses_rights_expires_and_retires_with_zero_real_funds_left() {
        var f = fixture(100);
        try {
            earnAvailable(f, "E1", 100);
            var held = redeem().hold(hold(f, "ORDER1", f.s1(), f.s2(), 100));
            assertThat(held.points()).isEqualTo(100);
            assertThat(read.pool(f.type()).reservedBMinor()).isEqualTo(100);
            assertThat(
                            context.getBean(PointsOperationsStore.class)
                                    .heldSubOrders(System.currentTimeMillis() + 1000, 200))
                    .contains(sub(f, "S1"), sub(f, "S2"));
            paidFinal(f, sub(f, "S1"), 60);
            paidFinal(f, sub(f, "S2"), 40);
            assertBalanced(f, "paid/finalized");
            var payout = payout(f, sub(f, "S1"));
            finishPayout(payout.ticketNo());
            refundZeroCash(f, sub(f, "S1"), 15);
            assertThat(
                            jdbc().queryForObject(
                                            "SELECT original_minor FROM pts_receivable WHERE"
                                                + " type_code=?",
                                            Long.class,
                                            f.type()))
                    .isEqualTo(15);
            assertBalanced(f, "paid refund creates real store R");
            var statement = statement(f, f.s2());
            var periodic =
                    context.getBean(StatementPayoutService.class)
                            .prepare("PERIODIC:" + f.type(), statement.statementNo(), 1, "CHECKER");
            assertThat(periodic.offset()).isZero();
            assertThat(periodic.cash()).isEqualTo(40);
            finishPayout(periodic.ticketNo());
            assertThat(read.pool(f.type()).d()).isZero();
            acceptance(f, f.s1(), "PERIODIC", "V2");
            var newTarget = target(f, sub(f, "S3"), f.s1(), 15);
            var second =
                    redeem().hold(
                                    new PointsRedeemPort.HoldCommand(
                                            "ORDER2:" + f.type(),
                                            "CN",
                                            "U",
                                            15,
                                            "ONLINE",
                                            "MINI",
                                            List.of(newTarget)));
            assertThat(second.points()).isEqualTo(15);
            paidFinal(f, sub(f, "S3"), 15);
            var offsetStatement = statement(f, f.s1());
            var offset =
                    context.getBean(StatementPayoutService.class)
                            .prepare(
                                    "OFFSET:" + f.type(),
                                    offsetStatement.statementNo(),
                                    1,
                                    "CHECKER");
            assertThat(offset.cash()).isZero();
            assertThat(offset.offset()).isEqualTo(15);
            assertThat(
                            context.getBean(PointsPayoutExecutionService.class)
                                    .execute(offset.ticketNo())
                                    .status())
                    .isEqualTo(FundsStatus.SUCCEEDED);
            assertThat(bank().sends).doesNotContainKey(offset.ticketNo());
            assertThat(
                            jdbc().queryForObject(
                                            "SELECT recovered_minor FROM pts_receivable WHERE"
                                                + " type_code=?",
                                            Long.class,
                                            f.type()))
                    .isEqualTo(15);
            assertBalanced(f, "full offset uses no fake zero cash evidence");
            for (String source : List.of(sub(f, "S1"), sub(f, "S2"), sub(f, "S3"), sub(f, "E1"))) {
                close(f, source);
            }
            assertThat(read.pool(f.type()).reservedBMinor()).isZero();
            assertThat(
                            read.account(f.type(), HolderType.RECEIVE, "PLATFORM")
                                    .points(Bucket.RECIRCULATABLE))
                    .isEqualTo(100);
            long minted = read.account(f.type(), HolderType.MINT, "PLATFORM").points(Bucket.ISSUED);
            var receipt = new EvidenceRef("REUSE:" + f.type(), "CNY", 100);
            posting.recordEvidence(receipt);
            var requests = context.getBean(PoolRequestService.class);
            requests.submit(
                    new PoolRequest(
                            "REUSE:" + f.type(),
                            f.type(),
                            PoolRequest.Action.TOPUP_U,
                            100,
                            "MAKER",
                            receipt.evidenceNo()));
            requests.approve("REUSE:" + f.type(), "CHECKER");
            assertThat(read.account(f.type(), HolderType.MINT, "PLATFORM").points(Bucket.ISSUED))
                    .isEqualTo(minted);
            assertThat(
                            read.account(f.type(), HolderType.RECEIVE, "PLATFORM")
                                    .points(Bucket.RECIRCULATABLE))
                    .isZero();
            earnAvailable(f, "E2", 100);
            close(f, sub(f, "E2"));
            // Explicit test clock fixture, not a product backdoor or a financial balance edit.
            jdbc().update(
                            "UPDATE pts_account SET"
                                + " expire_at=1000,last_active_at=0,expiry_revision=7 WHERE"
                                + " type_code=? AND holder_type='USER'",
                            f.type());
            var jobs = context.getBean(PointsJobServices.class);
            assertThat(
                            jobs.run(PointsJobServices.Task.LIFECYCLE, f.type(), 50, 2000, null)
                                    .processed())
                    .isEqualTo(1);
            jobs.run(PointsJobServices.Task.DISPATCH, f.type(), 50, 2000, null);
            assertThat(read.pool(f.type()).c()).isEqualTo(100);
            var switches = context.getBean(PointsSwitchService.class);
            switches.change(
                    new PointsSwitchService.Command(
                            "OFF:" + f.type(),
                            PointsSwitchService.Scope.GLOBAL,
                            "*",
                            "ALL",
                            false,
                            System.currentTimeMillis(),
                            "OP",
                            "STOP_NEW_PROMISES"));
            assertThat(context.getBean(EarnService.class).grant(earning(f, "OFF_EARN", 1)).reason())
                    .isEqualTo("POINTS_DISABLED");
            jobs.run(PointsJobServices.Task.DISPATCH, f.type(), 50, 4000, null);
            assertThat(read.pool(f.type()).c()).isZero();
            assertThat(read.account(f.type(), HolderType.USER, "U").points(Bucket.HELD)).isZero();
            assertBalanced(f, "income confirmation while new business is off");
            requests.submit(
                    new PoolRequest(
                            "EXIT:" + f.type(),
                            f.type(),
                            PoolRequest.Action.WITHDRAW_B,
                            200,
                            "MAKER",
                            null,
                            incomeAccount()));
            requests.approve("EXIT:" + f.type(), "CHECKER");
            var recovery = context.getBean(PointsRecoveryService.class);
            recovery.discover(f.type(), 50, 6000);
            recovery.discover(f.type(), 50, 6000);
            jobs.run(PointsJobServices.Task.DISPATCH, f.type(), 50, 6000, null);
            jobs.run(PointsJobServices.Task.DISPATCH, f.type(), 50, 8000, null);
            assertThat(read.pool(f.type()).b()).isZero();
            assertBalanced(f, "all real cash cleared");
            context.getBean(PointTypeService.class).drain(f.type(), "OP");
            assertThat(context.getBean(TypeRetirementService.class).retire(f.type(), "OP").status())
                    .isEqualTo(PointType.Status.RETIRED);
            var stats =
                    context.getBean(PointsStatsService.class)
                            .rebuild(f.type(), LocalDate.now(ZoneId.of("Asia/Shanghai")));
            assertThat(stats.metrics())
                    .containsEntry("granted_points", 200L)
                    .containsEntry("redeemed_points", 115L)
                    .containsEntry("payout_cash_minor", 100L)
                    .containsEntry("payout_offset_minor", 15L)
                    .containsEntry("expired_points", 100L);
            assertThat(bank().sends.values()).allMatch(count -> count == 1);
        } finally {
            context.getBean(PointsSwitchService.class)
                    .change(
                            new PointsSwitchService.Command(
                                    "ON:" + f.type(),
                                    PointsSwitchService.Scope.GLOBAL,
                                    "*",
                                    "ALL",
                                    true,
                                    System.currentTimeMillis(),
                                    "OP",
                                    "TEST_CLEANUP"));
        }
    }

    @Test
    void native_old_snapshot_cannot_omit_a_completed_pause_when_preparing_expiry()
            throws Exception {
        mysqlOnly();
        var f = fixture(100);
        earnAvailable(f, "E1", 100);
        overdue(f);
        var transaction = new TransactionTemplate(txManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(
                status -> {
                    read.account(f.type(), HolderType.USER, "U");
                    elsewhere(
                            () -> {
                                var switches = context.getBean(PointsSwitchService.class);
                                switches.change(
                                        new PointsSwitchService.Command(
                                                "PAUSE_OFF:" + f.type(),
                                                PointsSwitchService.Scope.TYPE,
                                                f.type(),
                                                f.type(),
                                                false,
                                                100,
                                                "OP",
                                                "MAINTENANCE"));
                                switches.change(
                                        new PointsSwitchService.Command(
                                                "PAUSE_ON:" + f.type(),
                                                PointsSwitchService.Scope.TYPE,
                                                f.type(),
                                                f.type(),
                                                true,
                                                500,
                                                "OP",
                                                "RECOVERED"));
                            });
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(
                                                            ai.neargo.shop.pay.points.lifecycle
                                                                    .ExpiryService.class)
                                                    .prepare(
                                                            new ai.neargo.shop.pay.points.lifecycle
                                                                    .ExpiryService.Request(
                                                                    f.type(),
                                                                    "U",
                                                                    7,
                                                                    1300,
                                                                    incomeAccount())))
                            .hasMessage("POINTS_EXPIRY_NOT_DUE");
                    status.setRollbackOnly();
                });
    }

    @Test
    void native_old_snapshot_cannot_accept_an_agreement_terminated_before_hold() throws Exception {
        mysqlOnly();
        var f = fixture(100);
        earnAvailable(f, "E1", 100);
        var transaction = new TransactionTemplate(txManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(
                status -> {
                    read.account(f.type(), HolderType.USER, "U");
                    elsewhere(
                            () ->
                                    new TransactionTemplate(txManager)
                                            .executeWithoutResult(
                                                    other -> {
                                                        var config =
                                                                context.getBean(
                                                                        PointsConfigurationStore
                                                                                .class);
                                                        config.lockTypeAndPool(f.type());
                                                        config.terminateAgreement(
                                                                PointsKeys.position(
                                                                        "A", f.type(), f.s1()),
                                                                "OWNER");
                                                    }));
                    var result = redeem().hold(hold(f, "ORDER1", f.s1(), f.s2(), 100));
                    assertThat(result.points()).isZero();
                    assertThat(result.reason()).isEqualTo("POINTS_STORE_NOT_ACCEPTING");
                });
    }

    private void mysqlOnly() throws Exception {
        try (var connection = dataSource.getConnection()) {
            Assumptions.assumeTrue(
                    connection.getMetaData().getDatabaseProductName().equals("MySQL"));
        }
    }

    private void elsewhere(Runnable work) {
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(work).get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void native_old_snapshot_cannot_grant_after_a_newly_committed_cancellation() throws Exception {
        mysqlOnly();
        var f = fixture(100);
        var transaction = new TransactionTemplate(txManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(
                status -> {
                    read.pool(f.type());
                    elsewhere(() -> deliver(fact(f, sub(f, "E2"), FactKind.CANCELLED, 1, 100)));
                    var result = context.getBean(EarnService.class).grant(earning(f, "E2", 20));
                    assertThat(result.status()).isEqualTo(PointsEarnPort.GrantStatus.SKIPPED);
                    assertThat(result.reason()).isEqualTo("POINTS_ORDER_CANCELLED");
                });
    }

    @Test
    void a_blank_earning_subject_is_rejected_before_any_rights_or_money_are_changed() {
        var f = fixture(100);
        var original = earning(f, "E1", 20);
        var invalid =
                new PointsEarnPort.GrantCommand(
                        original.eventNo(),
                        original.subOrderNo(),
                        "",
                        f.type(),
                        f.s1(),
                        original.payChannel(),
                        original.payScene(),
                        original.sourceVersion(),
                        original.lines(),
                        original.merchantNo(),
                        original.clientType());
        assertThatThrownBy(() -> context.getBean(EarnService.class).grant(invalid))
                .hasMessage("POINTS_EARN_INPUT_INVALID");
        assertThat(read.pool(f.type()).u()).isEqualTo(100);
        assertThat(read.pool(f.type()).c()).isZero();
    }

    @Test
    void a_plan_receipt_declaration_cannot_be_approved_without_verified_payment_evidence() {
        var f = fixture(100);
        var plans = context.getBean(TopupPlanService.class);
        var day = LocalDate.of(2026, 10, 16);
        String plan = "PLAN:" + f.type();
        plans.save(
                "SAVE:" + plan,
                new TopupPlanService.Plan(
                        plan, f.type(), PoolRequest.Action.TOPUP_U, 50, day, 1, true),
                "OP",
                "SYNTHETIC_PLAN");
        plans.tick(f.type(), day, 1);
        String request =
                plans.submitReceipt(plan, day, "NOT_VERIFIED:" + f.type(), "MAKER", "DECLARE_ONLY");
        assertThatThrownBy(
                        () -> context.getBean(PoolRequestService.class).approve(request, "CHECKER"))
                .hasMessage("POINTS_EVIDENCE_UNVERIFIED_OR_REUSED");
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT state FROM pts_pool_request WHERE request_no=?",
                                        String.class,
                                        request))
                .isEqualTo("SUBMITTED");
        assertThat(read.pool(f.type()).u()).isEqualTo(100);
    }

    @Test
    void pending_plan_periods_block_retirement_even_when_every_cash_and_points_balance_is_zero() {
        String type = "KP" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc().update(
                        "INSERT INTO"
                            + " pts_point_type(type_code,market,currency,rate_points,rate_minor,status)"
                            + " VALUES(?,'CN','CNY',1,1,'DRAINING')",
                        type);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')", type);
        var plans = context.getBean(TopupPlanService.class);
        var day = LocalDate.of(2026, 10, 16);
        String plan = "PLAN:" + type;
        plans.save(
                "SAVE:" + plan,
                new TopupPlanService.Plan(plan, type, PoolRequest.Action.TOPUP_U, 50, day, 1, true),
                "OP",
                "SYNTHETIC_PLAN");
        plans.tick(type, day, 1);
        var retirement = context.getBean(TypeRetirementService.class);
        assertThatThrownBy(() -> retirement.retire(type, "OP"))
                .hasMessage("POINTS_RETIREMENT_OBLIGATIONS_REMAIN");
        plans.save(
                "PAUSE:" + plan,
                new TopupPlanService.Plan(
                        plan, type, PoolRequest.Action.TOPUP_U, 50, day, 1, false),
                "OP",
                "PAUSE_FUTURE");
        assertThatThrownBy(() -> retirement.retire(type, "OP"))
                .hasMessage("POINTS_RETIREMENT_OBLIGATIONS_REMAIN");
        plans.cancelPeriod(plan, day, "OP", "CANCEL_UNFUNDED_PERIOD");
        assertThat(retirement.retire(type, "OP").status()).isEqualTo(PointType.Status.RETIRED);
    }

    @Test
    void partial_child_cancellation_then_order_release_restores_only_the_remaining_hold() {
        var f = fixture(100);
        earnAvailable(f, "E1", 100);
        redeem().hold(hold(f, "ORDER1", f.s1(), f.s2(), 100));
        deliver(fact(f, sub(f, "S1"), FactKind.CANCELLED, 2, 60));
        assertThat(read.account(f.type(), HolderType.USER, "U").points(Bucket.HELD)).isEqualTo(40);
        redeem().release(
                        new PointsRedeemPort.ReleaseCommand(
                                "ORDER1:" + f.type(), "RELEASE:" + f.type(), 3));
        assertThat(read.account(f.type(), HolderType.USER, "U").points(Bucket.AVAILABLE))
                .isEqualTo(100);
        assertThat(read.pool(f.type()).reservedBMinor()).isZero();
        assertBalanced(f, "partial cancel followed by root release");
    }

    @Test
    void cancellation_before_hold_prevents_a_late_order_from_freezing_points() {
        var f = fixture(100);
        earnAvailable(f, "E1", 100);
        deliver(fact(f, sub(f, "S2"), FactKind.CANCELLED, 2, 40));
        var result = redeem().hold(hold(f, "ORDER1", f.s1(), f.s2(), 100));
        assertThat(result.points()).isZero();
        assertThat(result.reason()).isEqualTo("POINTS_ORDER_CANCELLED");
        assertThat(read.account(f.type(), HolderType.USER, "U").points(Bucket.HELD)).isZero();
    }

    @Test
    void overdue_available_rights_cannot_be_renewed_by_another_earning_before_expiry_is_resolved() {
        var f = fixture(200);
        earnAvailable(f, "E1", 100);
        overdue(f);
        assertThatThrownBy(() -> context.getBean(EarnService.class).grant(earning(f, "E2", 20)))
                .hasMessage("POINTS_ACCOUNT_EXPIRY_PENDING");
        assertThat(read.account(f.type(), HolderType.USER, "U").expiryRevision()).isEqualTo(7);
        assertThat(read.pool(f.type()).u()).isEqualTo(100);
    }

    @Test
    void overdue_available_rights_cannot_create_a_new_hold_before_the_expiry_job_runs() {
        var f = fixture(100);
        earnAvailable(f, "E1", 100);
        overdue(f);
        var result = redeem().hold(hold(f, "ORDER1", f.s1(), f.s2(), 100));
        assertThat(result.points()).isZero();
        assertThat(result.reason()).isEqualTo("POINTS_EXPIRED");
        assertThat(read.account(f.type(), HolderType.USER, "U").points(Bucket.HELD)).isZero();
    }

    private void overdue(Fixture f) {
        jdbc().update(
                        "UPDATE pts_account SET expire_at=1000,last_active_at=0,expiry_revision=7"
                                + " WHERE type_code=? AND holder_type='USER'",
                        f.type());
    }

    private Fixture fixture(long u) {
        String type = "KA" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc().update(
                        "INSERT INTO"
                            + " pts_point_type(type_code,market,active_market,currency,rate_points,rate_minor,status,deduct_ratio_bp,idle_days)"
                            + " VALUES(?,'CN','CN','CNY',1,1,'ACTIVE',10000,30)",
                        type);
        activeTypes.add(type);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')", type);
        var proof = new EvidenceRef("CORE_INIT:" + type, "CNY", u + 200);
        posting.recordEvidence(proof);
        posting.post(
                new Voucher(
                        "CORE_INIT:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                move(type, HolderType.MINT, "PLATFORM", Bucket.ISSUED, -u),
                                move(type, HolderType.ISSUE, "PLATFORM", Bucket.ISSUABLE, u)),
                        List.of(new FundMove(FundBucket.U, u), new FundMove(FundBucket.B, 200)),
                        List.of(),
                        0,
                        List.of(proof),
                        "{}"));
        var fixture = new Fixture(type, "KS1:" + type, "KS2:" + type);
        acceptance(fixture, fixture.s1(), "PER_ORDER", "V1");
        acceptance(fixture, fixture.s2(), "PERIODIC", "V1");
        return fixture;
    }

    private void acceptance(Fixture f, String store, String mode, String version) {
        String key = PointsKeys.position("A", f.type(), store);
        jdbc().update(
                        "UPDATE pts_merchant_agreement SET"
                            + " active_key=NULL,state='TERMINATED',updated_at=CURRENT_TIMESTAMP"
                            + " WHERE active_key=?",
                        key);
        var agreement =
                new AgreementSnapshot(
                        "AGR:" + version + ":" + store,
                        f.type(),
                        "M:" + store,
                        store,
                        "OWNER",
                        "V1",
                        "HASH",
                        "SYNTHETIC CONTRACT",
                        mode,
                        "AGGREGATED",
                        "TEST",
                        "A1",
                        "ACTIVE");
        jdbc().update(
                        "INSERT INTO"
                            + " pts_merchant_agreement(type_code,state,payload_json,agreement_no,store_no,active_key,created_at,updated_at)"
                            + " VALUES(?,'ACTIVE',?,?,?,?,?,?)",
                        f.type(),
                        json.writeValueAsString(agreement),
                        PointsKeys.position("AG", f.type(), store + version),
                        store,
                        key,
                        Timestamp.from(Instant.EPOCH),
                        Timestamp.from(Instant.EPOCH));
    }

    private void earnAvailable(Fixture f, String suffix, long points) {
        var paid = fact(f, sub(f, suffix), FactKind.PAID, 1, 100);
        context.getBean(PointsFactPort.class).accept(paid);
        context.getBean(PointsFactPort.class).acceptEarning(earning(f, suffix, points));
        assertThat(worker().drain(f.type(), 20, 0)).isEqualTo(2);
        deliver(fact(f, sub(f, suffix), FactKind.FINALIZED, 2, 100));
    }

    private PointsEarnPort.GrantCommand earning(Fixture f, String suffix, long points) {
        return new PointsEarnPort.GrantCommand(
                "EARN:" + suffix + ":" + f.type(),
                sub(f, suffix),
                "U",
                f.type(),
                f.s1(),
                "TEST",
                "ONLINE",
                1,
                List.of(
                        new PointsEarnPort.EarnLine(
                                item(sub(f, suffix)),
                                "G",
                                "C",
                                1,
                                100,
                                0,
                                new PointsEarnPort.EarnRule(
                                        PointsEarnPort.RuleMode.FIXED, points))),
                "M:" + f.s1(),
                "MINI");
    }

    private PointsRedeemPort.HoldCommand hold(
            Fixture f, String order, String store1, String store2, long points) {
        return new PointsRedeemPort.HoldCommand(
                order + ":" + f.type(),
                "CN",
                "U",
                points,
                "ONLINE",
                "MINI",
                List.of(target(f, sub(f, "S1"), store1, 60), target(f, sub(f, "S2"), store2, 40)));
    }

    private PointsRedeemPort.Target target(Fixture f, String sub, String store, long goods) {
        return new PointsRedeemPort.Target(
                sub,
                "M:" + store,
                store,
                goods,
                "AGGREGATED",
                "TEST",
                "A1",
                "SM:" + store,
                "TRADE:" + sub,
                List.of(new PointsRedeemPort.Item(item(sub), goods)));
    }

    private LifecycleFact fact(Fixture f, String source, FactKind kind, long version, long goods) {
        var raw =
                new LifecycleFact(
                        kind + ":" + source,
                        null,
                        source,
                        "U",
                        f.type(),
                        version,
                        kind,
                        goods,
                        0,
                        List.of(new LineSnapshot(item(source), 1, goods, 0)),
                        Instant.parse("2026-10-15T00:00:00Z"));
        return new LifecycleFact(
                raw.eventNo(),
                PointsFactPortImpl.digest(raw),
                source,
                "U",
                f.type(),
                version,
                kind,
                goods,
                0,
                raw.lines(),
                raw.occurredAt());
    }

    private void paidFinal(Fixture f, String source, long goods) {
        deliver(fact(f, source, FactKind.PAID, 1, goods));
        deliver(fact(f, source, FactKind.FINALIZED, 2, goods));
    }

    private void deliver(LifecycleFact fact) {
        context.getBean(PointsFactPort.class).accept(fact);
        assertThat(worker().drain(fact.typeCode(), 20, 0)).isEqualTo(1);
    }

    private PointsSettlementHook.PayoutTicket payout(Fixture f, String source) {
        String no = PointsKeys.position("R", f.type(), source);
        var snapshot =
                json.readValue(
                        (String)
                                read.position(PositionMove.Kind.REDEEM.table(), no)
                                        .get("payload_json"),
                        RedeemSnapshot.class);
        return context.getBean(PointsPayoutService.class)
                .beginPayout(
                        new PayoutCommand(
                                "PAY:" + source,
                                f.type(),
                                source,
                                List.of(no),
                                FundsPurpose.INTERNAL_REPLENISHMENT,
                                snapshot.fundsContext(),
                                "CNY"));
    }

    private void finishPayout(String no) {
        var execution = context.getBean(PointsPayoutExecutionService.class);
        assertThat(execution.execute(no).status()).isEqualTo(FundsStatus.UNKNOWN);
        assertThat(execution.execute(no).status()).isEqualTo(FundsStatus.SUCCEEDED);
        assertThat(bank().sends.get(no)).isEqualTo(1);
    }

    private StoreStatementService.Snapshot statement(Fixture f, String store) {
        var service = context.getBean(StoreStatementService.class);
        var statement = service.draft(new Period(f.type(), store, 0, 4102444800000L), "MAKER");
        return service.confirm(statement.statementNo(), 1, "CHECKER");
    }

    private void refundZeroCash(Fixture f, String source, long goods) {
        String no = PointsKeys.position("R", f.type(), source);
        var redemption =
                json.readValue(
                        (String)
                                read.position(PositionMove.Kind.REDEEM.table(), no)
                                        .get("payload_json"),
                        RedeemSnapshot.class);
        var route = redemption.fundsContext();
        String after = "AS:" + source;
        var request =
                new PointsRefundPort.RefundCommand(
                        after,
                        source,
                        "U",
                        f.type(),
                        "CNY",
                        3,
                        0,
                        goods,
                        List.of(new PointsRefundPort.RefundLine(item(source), goods)),
                        new PointsRefundPort.PaymentSnapshot(
                                route.path().name(),
                                route.channel(),
                                route.merchantNo(),
                                route.storeNo(),
                                route.accountVersion(),
                                route.subMerchantId(),
                                route.tradeNo(),
                                route.outTradeNo(),
                                route.orderTotalMinor()));
        var prepared = context.getBean(PointsRefundPreparationService.class).prepare(request);
        var zero =
                new PointsRefundPort.ZeroCashCompletionFact(
                        "ZERO:" + source,
                        prepared.preparationNo(),
                        prepared.inputHash(),
                        prepared.revision(),
                        f.type(),
                        after,
                        source,
                        "U",
                        route.storeNo(),
                        "CNY",
                        3,
                        0,
                        0,
                        0,
                        PointsRefundPort.ZeroCashReason.NO_CASH_DUE,
                        Instant.parse("2026-10-16T00:00:00Z"));
        var facts = context.getBean(PointsFactPort.class);
        facts.acceptZeroCash(zero);
        facts.acceptRefundConfirmation(
                new RefundConfirmationFact(
                        "REFUND_DONE:" + source,
                        f.type(),
                        source,
                        "U",
                        3,
                        Instant.parse("2026-10-16T00:00:00Z"),
                        new PointsRefundPort.RefundConfirmation(
                                prepared.preparationNo(),
                                prepared.inputHash(),
                                null,
                                null,
                                null,
                                zero.eventNo())));
        assertThat(worker().drain(f.type(), 20, 0)).isEqualTo(2);
    }

    private void close(Fixture f, String source) {
        context.getBean(PointsFactPort.class)
                .acceptClosure(
                        new RefundClosureFact(
                                "CLOSE:" + source,
                                f.type(),
                                source,
                                "U",
                                4,
                                Instant.parse("2026-10-15T01:00:00Z"),
                                true,
                                true,
                                Instant.parse("2026-10-16T00:00:00Z")));
        assertThat(worker().drain(f.type(), 20, 0)).isEqualTo(1);
    }

    private void assertBalanced(Fixture f, String phase) {
        var report =
                context.getBean(PointsReconService.class)
                        .scan(f.type(), System.currentTimeMillis());
        assertThat(report.complete()).as(phase).isTrue();
        assertThat(report.differences()).as(phase).isEmpty();
    }

    private static FundsContext incomeAccount() {
        return new FundsContext(
                FundsPath.AGGREGATED,
                "TEST",
                "PLATFORM",
                "PLATFORM",
                "PLATFORM_ACCOUNT_V1",
                "PLATFORM_TEST",
                null,
                null,
                0);
    }

    private String sub(Fixture f, String suffix) {
        return suffix + ":" + f.type();
    }

    private String item(String source) {
        return source.substring(0, source.indexOf(':')) + "_ITEM";
    }

    private PointsMove move(String type, HolderType holder, String no, Bucket bucket, long points) {
        return new PointsMove(new AccountKey(type, holder, no, bucket), points);
    }

    private PointsEventWorker worker() {
        return context.getBean(PointsEventWorker.class);
    }

    private RedeemService redeem() {
        return context.getBean(RedeemService.class);
    }

    private PointsPayoutPreparationTest.LostReplyFunds bank() {
        return context.getBean(PointsPayoutPreparationTest.LostReplyFunds.class);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    record Fixture(String type, String s1, String s2) {}

    @TestConfiguration(proxyBeanMethods = false)
    @Import({
        PointsPayoutPreparationTest.Config.class,
        RedeemServiceImpl.class,
        PoolRequestServiceImpl.class,
        PoolServiceImpl.class,
        PointTypeServiceImpl.class
    })
    static class Config {
        @Bean
        MarketService markets() {
            return mock(MarketService.class);
        }

        @Bean
        PointsIncomeAccountProvider income() {
            return type -> incomeAccount();
        }

        @Bean
        AgreementService acceptance(PointsConfigurationStore store) {
            return new AgreementService() {
                private final ObjectMapper json = new ObjectMapper();

                @Override
                public AgreementSnapshot active(String type, String no) {
                    var row = store.agreement(PointsKeys.position("A", type, no));
                    return row == null
                            ? null
                            : json.readValue(
                                    (String) row.get("payload_json"), AgreementSnapshot.class);
                }

                @Override
                public void publishTerms(String type, String version, String text, String actor) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public AgreementSnapshot sign(SignCommand c, String actor) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void terminate(String type, String no, String actor) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }
}
