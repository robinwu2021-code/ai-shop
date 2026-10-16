package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.neargo.shop.pay.points.app.PointsSettlementHook;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutCommand;
import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.pay.points.earn.impl.EarnServiceImpl;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsRefundPortImpl;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.refund.PointsReceivableRecoveryExecutionService;
import ai.neargo.shop.pay.points.refund.PointsReceivableRecoveryService;
import ai.neargo.shop.pay.points.refund.PointsRefundCashDecisionService;
import ai.neargo.shop.pay.points.refund.PointsRefundCollectionExecutionService;
import ai.neargo.shop.pay.points.refund.PointsRefundCollectionService;
import ai.neargo.shop.pay.points.refund.PointsRefundCompensationExecutionService;
import ai.neargo.shop.pay.points.refund.PointsRefundCompensationService;
import ai.neargo.shop.pay.points.refund.PointsRefundZeroCashService;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsCashRefundReadStore;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsConfigurationStore;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsFactStore;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsReadStore;
import ai.neargo.shop.spi.points.PointsEarnPort;
import ai.neargo.shop.spi.points.PointsFactPort.FactKind;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import ai.neargo.shop.spi.points.PointsFactPort.LineSnapshot;
import ai.neargo.shop.spi.points.PointsRedeemPort.Item;
import ai.neargo.shop.spi.points.PointsRedeemPort.Share;
import ai.neargo.shop.spi.points.PointsRedeemPort.Target;
import ai.neargo.shop.spi.points.PointsRefundPort;
import ai.neargo.shop.spi.points.PointsRefundPort.PaymentSnapshot;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundAbort;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundCommand;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundConfirmation;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundLine;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundPreparation;
import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashCompletionFact;
import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashReason;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * Uses the real pay writer and synthetic orders; preparing never credits points or real issuance
 * money.
 */
@SpringJUnitConfig(PointsRefundPreparationTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
        properties = {
            "shop.points.enabled=true",
            "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_refund_prepare;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
            "spring.datasource.username=${points.jdbc.user:sa}",
            "spring.datasource.password=${points.jdbc.password:}",
            "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
        })
class PointsRefundPreparationTest {
    @Autowired private PointsRefundPort refunds;
    @Autowired private EarnService earn;
    @Autowired private PointsRefundPort confirmations;
    @Autowired private PointsRefundPort aborts;
    @Autowired private PointsRefundCollectionExecutionService collections;
    @Autowired private PointsRefundCollectionService collectionDecisions;
    @Autowired private PointsRefundCashDecisionService cashDecisions;
    @Autowired private PointsRefundZeroCashService zeroCash;
    @Autowired private PointsRefundCompensationService compensations;
    @Autowired private PointsRefundCompensationExecutionService compensationExecution;
    @Autowired private TestFunds funds;
    @Autowired private PointsSettlementHook settlement;
    @Autowired private PointsPayoutExecutionService payoutExecution;
    @Autowired private PointsReceivableRecoveryService recoveries;
    @Autowired private PointsReceivableRecoveryExecutionService recoveryExecution;
    @Autowired private PointsPostingService posting;

    @Autowired
    @Qualifier("payTxManager")
    private PlatformTransactionManager payTransactions;

    @Autowired
    @Qualifier("payDataSource")
    private DataSource dataSource;

    @BeforeAll
    void schema() {
        PointsPostingDatabaseTest.initializeSchema(dataSource);
        jdbc().execute(
                        "CREATE TABLE IF NOT EXISTS stl_payment(payment_no VARCHAR(64) PRIMARY"
                            + " KEY,direction VARCHAR(24),after_sale_no VARCHAR(40),sub_order_no"
                            + " VARCHAR(40),user_no VARCHAR(40),currency VARCHAR(8),amount_minor"
                            + " BIGINT,status VARCHAR(24),pay_channel VARCHAR(32),trade_no"
                            + " VARCHAR(64),succeeded_at BIGINT,deleted TINYINT DEFAULT 0)");
        try (var connection = dataSource.getConnection();
                var columns =
                        connection
                                .getMetaData()
                                .getColumns(connection.getCatalog(), null, "stl_payment", null)) {
            boolean installed = false;
            while (columns.next()) {
                installed |=
                        "refund_verified_minor".equalsIgnoreCase(columns.getString("COLUMN_NAME"));
            }
            if (!installed) {
                new ResourceDatabasePopulator(
                                new ClassPathResource(
                                        "db/migration/V413__refund_verified_cash_evidence.sql"))
                        .execute(dataSource);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Refund schema fixture failed", failure);
        }
    }

    @Test
    void refund_waits_for_unknown_payout_then_uses_the_confirmed_paid_state() {
        String type = fixture(false, true);
        addRedemption(type, false);
        var context =
                new PointsFundsGateway.FundsContext(
                        PointsFundsGateway.FundsPath.DIRECT,
                        "TEST",
                        "MERCHANT",
                        "STORE",
                        "V1",
                        "SUB",
                        "TRADE",
                        "OUT",
                        100);
        var payout =
                settlement.beginPayout(
                        new PayoutCommand(
                                "BILL:" + type,
                                type,
                                type,
                                List.of(PointsKeys.position("R", type, type)),
                                PointsFundsGateway.FundsPurpose.SUBSIDY,
                                context,
                                "CNY"));
        assertThatThrownBy(() -> refunds.prepare(command(type, "AS:" + type, 50)))
                .hasMessage("POINTS_REFUND_SOURCE_BUSY");
        assertThat(payoutExecution.execute(payout.ticketNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.UNKNOWN);
        assertThatThrownBy(() -> refunds.prepare(command(type, "AS:" + type, 50)))
                .hasMessage("POINTS_REFUND_SOURCE_BUSY");
        assertThat(payoutExecution.execute(payout.ticketNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.SUCCEEDED);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        assertThat(prepared.returnedPoints()).isEqualTo(20);
        assertThat(pool(type, "d_minor")).isZero();
        assertThat(balance(type, "held_points")).isEqualTo(10);
        assertThat(funds.sends.get(payout.ticketNo())).isEqualTo(1);
        assertThat(funds.queries.get(payout.ticketNo())).isEqualTo(1);
    }

    @Test
    void prepared_refund_prevents_a_new_payout_without_spending_d() {
        String type = fixture(false, true);
        addRedemption(type, false);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        assertThatThrownBy(() -> settlement.beginPayout(payoutCommand(type)))
                .hasMessage("POINTS_POSITION_NOT_PAYABLE");
        assertThat(pool(type, "d_minor")).isEqualTo(40);
        assertThat(refundState(prepared)).isEqualTo("PREPARED");
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_payout_ticket WHERE type_code=?",
                                        Long.class,
                                        type))
                .isZero();
        assertThat(balance(type, "held_points")).isEqualTo(10);
    }

    @Test
    void failed_payout_unlocks_refund_and_late_success_cannot_overwrite_the_failure() {
        String type = fixture(false, true);
        addRedemption(type, false);
        var ticket = settlement.beginPayout(payoutCommand(type));
        funds.failTickets.add(ticket.ticketNo());
        assertThat(payoutExecution.execute(ticket.ticketNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.FAILED);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        assertThat(prepared.returnedPoints()).isEqualTo(20);
        assertThatThrownBy(
                        () ->
                                settlement.acceptResult(
                                        new PointsFundsGateway.FundsResult(
                                                ticket.ticketNo(),
                                                PointsFundsGateway.FundsStatus.SUCCEEDED,
                                                "LATE:" + type,
                                                40,
                                                null)))
                .hasMessage("POINTS_FUNDS_RESULT_CONFLICT");
        assertThat(pool(type, "d_minor")).isEqualTo(40);
        assertThat(refundState(prepared)).isEqualTo("PREPARED");
        assertThat(funds.sends.get(ticket.ticketNo())).isEqualTo(1);
    }

    @Test
    void concurrent_payout_and_refund_have_only_one_owner_of_the_redemption() throws Exception {
        String type = fixture(false, true);
        addRedemption(type, false);
        var barrier = new CyclicBarrier(2);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var payout =
                    threads.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                return catchThrowable(
                                        () -> settlement.beginPayout(payoutCommand(type)));
                            });
            var refund =
                    threads.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                return catchThrowable(
                                        () -> refunds.prepare(command(type, "AS:" + type, 50)));
                            });
            var payoutFailure = payout.get(15, TimeUnit.SECONDS);
            var refundFailure = refund.get(15, TimeUnit.SECONDS);
            assertThat((payoutFailure == null ? 1 : 0) + (refundFailure == null ? 1 : 0))
                    .isEqualTo(1);
            if (payoutFailure != null) {
                assertThat(payoutFailure).hasMessage("POINTS_POSITION_NOT_PAYABLE");
            } else {
                assertThat(refundFailure).hasMessage("POINTS_REFUND_SOURCE_BUSY");
            }
            assertThat(
                            jdbc().queryForObject(
                                            "SELECT state FROM pts_redeem_position WHERE"
                                                    + " position_no=?",
                                            String.class,
                                            PointsKeys.position("R", type, type)))
                    .isEqualTo(payoutFailure == null ? "PAYING" : "REFUND_PREPARED");
            assertThat(balance(type, "held_points")).isEqualTo(payoutFailure == null ? 0 : 10);
            assertThat(pool(type, "d_minor")).isEqualTo(40);
        }
    }

    private PayoutCommand payoutCommand(String type) {
        var context =
                new PointsFundsGateway.FundsContext(
                        PointsFundsGateway.FundsPath.DIRECT,
                        "TEST",
                        "MERCHANT",
                        "STORE",
                        "V1",
                        "SUB",
                        "TRADE",
                        "OUT",
                        100);
        return new PayoutCommand(
                "BILL:" + type,
                type,
                type,
                List.of(PointsKeys.position("R", type, type)),
                PointsFundsGateway.FundsPurpose.SUBSIDY,
                context,
                "CNY");
    }

    @Test
    void pending_grants_are_frozen_once_with_immutable_input_and_no_early_return() {
        String type = fixture(false, true);
        var command = command(type, "AS:" + type, 50);
        var result = refunds.prepare(command);
        assertThat(result.reclaimedPending()).isEqualTo(10);
        assertThat(result.reclaimedAvailable()).isZero();
        assertThat(result.netCashMinor()).isEqualTo(50);
        assertThat(result.returnedPoints()).isZero();
        assertThat(refunds.prepare(command)).isEqualTo(result);
        assertThat(balance(type, "pending_points")).isEqualTo(10);
        assertThat(balance(type, "held_points")).isEqualTo(10);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThat(pool(type, "c_minor")).isEqualTo(20);
        assertThatThrownBy(() -> refunds.prepare(command(type, "AS:" + type, 100)))
                .hasMessage("POINTS_REFUND_INPUT_CONFLICT");
        assertThatThrownBy(() -> refunds.prepare(command(type, "SECOND:" + type, 100)))
                .hasMessage("POINTS_REFUND_SOURCE_BUSY");
    }

    @Test
    void spent_available_points_use_bounded_collection_plan_without_cash_credit() {
        String type = fixture(true, true);
        posting.post(
                new Voucher(
                        "SPEND:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(move(type, Bucket.AVAILABLE, -15), move(type, Bucket.HELD, 15)),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        var result = refunds.prepare(command(type, "AS:" + type, 50));
        assertThat(result.reclaimedAvailable()).isEqualTo(5);
        assertThat(result.expectedClawbackPoints()).isEqualTo(5);
        assertThat(result.cashOffsetMinor()).isEqualTo(5);
        assertThat(result.netCashMinor()).isEqualTo(45);
        assertThat(result.collectionTicket()).isNotBlank();
        assertThat(result.state()).isEqualTo("COLLECTION_REQUIRED");
        assertThat(balance(type, "available_points")).isZero();
        assertThat(balance(type, "held_points")).isEqualTo(20);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
    }

    @Test
    void grant_only_refund_rejects_another_stores_collection_route_before_freezing() {
        String type = fixture(true, true);
        var c = command(type, "FOREIGN:" + type, 50);
        var p = c.payment();
        var foreign =
                new PaymentSnapshot(
                        p.fundsPath(),
                        p.channel(),
                        "OTHER_MERCHANT",
                        "OTHER_STORE",
                        p.accountVersion(),
                        p.subMerchantId(),
                        p.tradeNo(),
                        p.outTradeNo(),
                        p.orderTotalMinor());
        var changed =
                new RefundCommand(
                        c.afterSaleNo(),
                        c.subOrderNo(),
                        c.userNo(),
                        c.typeCode(),
                        c.currency(),
                        c.sourceVersion(),
                        c.businessCashMinor(),
                        c.cumulativeGoodsMinor(),
                        c.lines(),
                        foreign);
        assertThatThrownBy(() -> refunds.prepare(changed))
                .hasMessage("POINTS_REFUND_STORE_MISMATCH");
        assertThat(balance(type, "available_points")).isEqualTo(20);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_refund_preparation WHERE"
                                                + " type_code=?",
                                        Long.class,
                                        type))
                .isZero();
    }

    @Test
    void grant_only_refund_requires_original_payment_snapshot_and_preserves_it_on_replay() {
        var json = new ObjectMapper();
        String type = fixture(true, true);
        var c = command(type, "BANK:" + type, 50);
        var p = c.payment();
        var changed =
                new RefundCommand(
                        c.afterSaleNo(),
                        c.subOrderNo(),
                        c.userNo(),
                        c.typeCode(),
                        c.currency(),
                        c.sourceVersion(),
                        c.businessCashMinor(),
                        c.cumulativeGoodsMinor(),
                        c.lines(),
                        new PaymentSnapshot(
                                p.fundsPath(),
                                p.channel(),
                                p.merchantNo(),
                                p.storeNo(),
                                p.accountVersion(),
                                "OTHER_SUB",
                                p.tradeNo(),
                                p.outTradeNo(),
                                p.orderTotalMinor()));
        assertThatThrownBy(() -> refunds.prepare(changed))
                .hasMessage("POINTS_REFUND_STORE_MISMATCH");
        // Only fixture metadata is altered to emulate an incomplete authoritative source contract.
        String grantNo = PointsKeys.position("G", type, type);
        var payload =
                (tools.jackson.databind.node.ObjectNode)
                        json.readTree(
                                jdbc().queryForObject(
                                                "SELECT payload_json FROM pts_grant_position WHERE"
                                                        + " position_no=?",
                                                String.class,
                                                grantNo));
        var original = json.treeToValue(payload.get("command"), PointsEarnPort.GrantCommand.class);
        var replay =
                new PointsEarnPort.GrantCommand(
                        original.eventNo(),
                        original.subOrderNo(),
                        original.userNo(),
                        original.typeCode(),
                        original.storeNo(),
                        original.payChannel(),
                        original.payScene(),
                        original.sourceVersion(),
                        original.lines(),
                        original.merchantNo(),
                        original.clientType(),
                        changed.payment());
        assertThatThrownBy(() -> earn.grant(replay)).hasMessage("POINTS_IDEMPOTENCY_CONFLICT");
        ((tools.jackson.databind.node.ObjectNode) payload.get("command")).remove("payment");
        jdbc().update(
                        "UPDATE pts_grant_position SET payload_json=? WHERE position_no=?",
                        json.writeValueAsString(payload),
                        grantNo);
        assertThatThrownBy(() -> refunds.prepare(c))
                .hasMessage("POINTS_REFUND_PAYMENT_SNAPSHOT_REQUIRED");
        assertThat(balance(type, "available_points")).isEqualTo(20);
    }

    @Test
    void redemption_refund_rejects_changed_original_bank_identifiers() {
        for (int field = 0; field < 3; field++) {
            String type = fixture(false, true);
            addRedemption(type, false);
            var c = command(type, "ROUTE:" + type, 50);
            var p = c.payment();
            var route =
                    new PaymentSnapshot(
                            p.fundsPath(),
                            p.channel(),
                            p.merchantNo(),
                            p.storeNo(),
                            p.accountVersion(),
                            field == 0 ? "OTHER_SUB" : p.subMerchantId(),
                            field == 1 ? "OTHER_TRADE" : p.tradeNo(),
                            field == 2 ? "OTHER_OUT" : p.outTradeNo(),
                            p.orderTotalMinor());
            var changed =
                    new RefundCommand(
                            c.afterSaleNo(),
                            c.subOrderNo(),
                            c.userNo(),
                            c.typeCode(),
                            c.currency(),
                            c.sourceVersion(),
                            c.businessCashMinor(),
                            c.cumulativeGoodsMinor(),
                            c.lines(),
                            route);
            assertThatThrownBy(() -> refunds.prepare(changed))
                    .hasMessage("POINTS_REFUND_STORE_MISMATCH");
            assertThat(
                            jdbc().queryForObject(
                                            "SELECT COUNT(*) FROM pts_refund_preparation WHERE"
                                                    + " type_code=?",
                                            Long.class,
                                            type))
                    .isZero();
        }
    }

    @Test
    void activated_grant_still_rejects_a_final_fact_with_unapplied_refund() {
        String type = fixture(true, true);
        var fact =
                new LifecycleFact(
                        "UNAPPLIED_ACTIVE:" + type,
                        "HASH",
                        type,
                        "USER",
                        type,
                        2,
                        FactKind.FINALIZED,
                        100,
                        1,
                        List.of(new LineSnapshot("ITEM", 1, 100, 1)),
                        Instant.parse("2026-10-16T00:00:00Z"));
        assertThatThrownBy(() -> settlement.onBillFinalized(fact))
                .hasMessage("POINTS_REFUND_FACT_NOT_APPLIED");
        assertThat(balance(type, "available_points")).isEqualTo(20);
    }

    @Test
    void finalization_accepts_refunds_already_deducted_before_grant() {
        String type = fixture(false, true, 50);
        long pending = balance(type, "pending_points");
        assertThat(pending).isPositive();
        var fact =
                new LifecycleFact(
                        "LATE_FINAL:" + type,
                        "HASH",
                        type,
                        "USER",
                        type,
                        2,
                        FactKind.FINALIZED,
                        100,
                        50,
                        List.of(new LineSnapshot("ITEM", 1, 100, 50)),
                        Instant.parse("2026-10-16T00:00:00Z"));
        settlement.onBillFinalized(fact);
        settlement.onBillFinalized(fact);
        assertThat(balance(type, "pending_points")).isZero();
        assertThat(balance(type, "available_points")).isEqualTo(pending);
    }

    @Test
    void skipped_grants_do_not_claw_back_expected_but_unissued_points() {
        String type = fixture(false, false);
        var result = refunds.prepare(command(type, "AS:" + type, 100));
        assertThat(result.reclaimedPending()).isZero();
        assertThat(result.expectedClawbackPoints()).isZero();
        assertThat(result.cashOffsetMinor()).isZero();
        assertThat(pool(type, "c_minor")).isZero();
    }

    @Test
    void invalid_line_total_and_subject_fail_before_freezing() {
        String type = fixture(false, true);
        var command = command(type, "AS:" + type, 50);
        var invalid =
                new RefundCommand(
                        command.afterSaleNo(),
                        type,
                        "USER",
                        type,
                        "CNY",
                        1,
                        50,
                        51,
                        command.lines(),
                        command.payment());
        assertThatThrownBy(() -> refunds.prepare(invalid))
                .hasMessage("POINTS_REFUND_LINES_INVALID");
        var foreign =
                new RefundCommand(
                        command.afterSaleNo(),
                        type,
                        "OTHER",
                        type,
                        "CNY",
                        1,
                        50,
                        50,
                        command.lines(),
                        command.payment());
        assertThatThrownBy(() -> refunds.prepare(foreign))
                .hasMessage("POINTS_REFUND_SUBJECT_MISMATCH");
        assertThat(balance(type, "pending_points")).isEqualTo(20);
        assertThat(balance(type, "held_points")).isZero();
    }

    @Test
    void confirmation_requires_exact_successful_pay_ledger_binding_and_replays_once() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        cashDecisions.authorize(
                prepared.preparationNo(), prepared.inputHash(), prepared.revision());
        String payment = "CASH:" + type;
        jdbc().update(
                        "INSERT INTO"
                            + " stl_payment(payment_no,direction,after_sale_no,sub_order_no,user_no,currency,amount_minor,status,pay_channel)"
                            + " VALUES(?,'REFUND',?,?,'USER','CNY',50,'PENDING','TEST')",
                        payment,
                        "AS:" + type,
                        type);
        var confirmation =
                new RefundConfirmation(
                        prepared.preparationNo(), prepared.inputHash(), payment, null, null);
        assertThatThrownBy(() -> confirmations.confirm(confirmation))
                .hasMessage("POINTS_CASH_REFUND_NOT_CONFIRMED");
        assertThat(balance(type, "held_points")).isEqualTo(10);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        jdbc().update(
                        "UPDATE stl_payment SET status='SUCCESS',trade_no='RECEIPT',succeeded_at=1,"
                                + "after_sale_no='OTHER' WHERE payment_no=?",
                        payment);
        assertThatThrownBy(() -> confirmations.confirm(confirmation))
                .hasMessage("POINTS_CASH_REFUND_NOT_CONFIRMED");
        jdbc().update(
                        "UPDATE stl_payment SET refund_verified_minor=50,refund_verified_at=1,"
                                + "refund_verified_provider_no=? WHERE payment_no=?",
                        "VERIFIED:" + type,
                        payment);
        assertThatThrownBy(() -> confirmations.confirm(confirmation))
                .hasMessage("POINTS_CASH_REFUND_MISMATCH");
        jdbc().update(
                        "UPDATE stl_payment SET after_sale_no=?,amount_minor=49 WHERE payment_no=?",
                        "AS:" + type,
                        payment);
        assertThatThrownBy(() -> confirmations.confirm(confirmation))
                .hasMessage("POINTS_CASH_REFUND_MISMATCH");
        jdbc().update("UPDATE stl_payment SET amount_minor=50 WHERE payment_no=?", payment);
        var outcome = confirmations.confirm(confirmation);
        assertThat(outcome.reclaimedPoints()).isEqualTo(10);
        assertThat(outcome.replayed()).isFalse();
        assertThat(confirmations.confirm(confirmation).replayed()).isTrue();
        assertThat(balance(type, "held_points")).isZero();
        assertThat(balance(type, "pending_points")).isEqualTo(10);
        assertThat(pool(type, "u_minor")).isEqualTo(90);
        assertThat(pool(type, "c_minor")).isEqualTo(10);
        assertThatThrownBy(
                        () ->
                                confirmations.confirm(
                                        new RefundConfirmation(
                                                prepared.preparationNo(),
                                                prepared.inputHash(),
                                                "OTHER",
                                                null,
                                                null)))
                .hasMessage("POINTS_REFUND_CONFIRMATION_CONFLICT");
        var next = refunds.prepare(command(type, "SECOND:" + type, 100));
        assertThat(next.reclaimedPending()).isEqualTo(10);
    }

    @Test
    void unpaid_redemption_returns_from_d_and_releases_reserve_without_a_loan() {
        String type = fixture(false, true);
        addRedemption(type, false);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        assertThat(prepared.returnedPoints()).isEqualTo(20);
        assertThat(balance(type, "available_points")).isZero();
        assertThat(pool(type, "d_minor")).isEqualTo(40);
        var outcome = confirmations.confirm(successfulCash(type, prepared));
        assertThat(outcome.receivableMinor()).isZero();
        assertThat(balance(type, "available_points")).isEqualTo(20);
        assertThat(pool(type, "d_minor")).isEqualTo(20);
        assertThat(pool(type, "c_minor")).isEqualTo(30);
        assertThat(pool(type, "reserved_b_minor")).isEqualTo(20);
        assertThat(pool(type, "b_minor")).isEqualTo(40);
    }

    @Test
    void paid_redemption_uses_b_with_store_receivable_and_replay_cannot_duplicate_return() {
        String type = fixture(false, true);
        addRedemption(type, true);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var confirmation = successfulCash(type, prepared);
        var outcome = confirmations.confirm(confirmation);
        assertThat(outcome.receivableMinor()).isEqualTo(20);
        assertThat(confirmations.confirm(confirmation).replayed()).isTrue();
        assertThat(balance(type, "available_points")).isEqualTo(20);
        assertThat(pool(type, "d_minor")).isZero();
        assertThat(pool(type, "c_minor")).isEqualTo(30);
        assertThat(pool(type, "b_minor")).isEqualTo(20);
        assertThat(pool(type, "reserved_b_minor")).isEqualTo(20);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT original_minor FROM pts_receivable WHERE"
                                                + " type_code=? AND store_no='STORE'",
                                        Long.class,
                                        type))
                .isEqualTo(20);
    }

    @Test
    void cash_unknown_cannot_abort_and_definite_failure_restores_original_buckets() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        cashDecisions.authorize(
                prepared.preparationNo(), prepared.inputHash(), prepared.revision());
        String payment = "CASH:" + type;
        jdbc().update(
                        "INSERT INTO"
                            + " stl_payment(payment_no,direction,after_sale_no,sub_order_no,user_no,currency,amount_minor,status,pay_channel)"
                            + " VALUES(?,'REFUND',?,?,'USER','CNY',50,'PENDING','TEST')",
                        payment,
                        "AS:" + type,
                        type);
        var abort = new RefundAbort(prepared.preparationNo(), prepared.inputHash(), payment);
        assertThatThrownBy(() -> aborts.abort(abort))
                .hasMessage("POINTS_REFUND_CASH_OUTCOME_UNKNOWN");
        assertThat(balance(type, "held_points")).isEqualTo(10);
        jdbc().update("UPDATE stl_payment SET status='FAILED' WHERE payment_no=?", payment);
        aborts.abort(abort);
        aborts.abort(abort);
        assertThat(balance(type, "pending_points")).isEqualTo(20);
        assertThat(balance(type, "held_points")).isZero();
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThatThrownBy(() -> refunds.prepare(command(type, "AS:" + type, 50)))
                .hasMessage("POINTS_REFUND_PREPARATION_ABORTED");
        assertThat(refunds.prepare(command(type, "SECOND:" + type, 100)).reclaimedPending())
                .isEqualTo(20);
    }

    @Test
    void collection_unknown_queries_original_intent_and_only_cash_confirmation_credits_u() {
        String type = fixture(true, true);
        posting.post(
                new Voucher(
                        "SPEND:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(move(type, Bucket.AVAILABLE, -20), move(type, Bucket.HELD, 20)),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        assertThat(prepared.cashOffsetMinor()).isEqualTo(10);
        assertThat(collections.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.UNKNOWN);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThat(collections.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.SUCCEEDED);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        var confirmation = successfulCash(type, prepared);
        var bound =
                new RefundConfirmation(
                        confirmation.preparationNo(),
                        confirmation.inputHash(),
                        confirmation.cashRefundPaymentNo(),
                        prepared.collectionTicket(),
                        null);
        var outcome = confirmations.confirm(bound);
        assertThat(outcome.collectedMinor()).isEqualTo(10);
        assertThat(outcome.reclaimedPoints()).isZero();
        assertThat(pool(type, "u_minor")).isEqualTo(90);
        assertThat(pool(type, "c_minor")).isEqualTo(20);
        assertThat(confirmations.confirm(bound).replayed()).isTrue();
        assertThat(funds.sends.get(prepared.collectionTicket())).isEqualTo(1);
        assertThat(funds.queries.get(prepared.collectionTicket())).isEqualTo(1);
    }

    @Test
    void finalization_cannot_activate_points_held_by_a_prepared_refund() {
        String type = fixture(false, true);
        refunds.prepare(command(type, "AS:" + type, 50));
        var fact =
                new LifecycleFact(
                        "FINAL:" + type,
                        "HASH",
                        type,
                        "USER",
                        type,
                        2,
                        FactKind.FINALIZED,
                        100,
                        0,
                        List.of(new LineSnapshot("ITEM", 1, 100, 0)),
                        Instant.now());
        assertThatThrownBy(() -> settlement.onBillFinalized(fact))
                .hasMessage("POINTS_REFUND_SOURCE_BUSY");
        assertThat(balance(type, "pending_points")).isEqualTo(10);
        assertThat(balance(type, "available_points")).isZero();
        assertThat(balance(type, "held_points")).isEqualTo(10);
    }

    @Test
    void unsupported_collection_waives_missing_points_without_issuance() {
        String type = fixture(true, true);
        posting.post(
                new Voucher(
                        "SPEND:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(move(type, Bucket.AVAILABLE, -20), move(type, Bucket.HELD, 20)),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        funds.supported = false;
        try {
            var prepared = refunds.prepare(command(type, "AS:" + type, 50));
            assertThat(prepared.cashOffsetMinor()).isZero();
            assertThat(prepared.waivedPoints()).isEqualTo(10);
            var outcome = confirmations.confirm(successfulCash(type, prepared));
            assertThat(outcome.reclaimedPoints()).isZero();
            assertThat(outcome.waivedPoints()).isEqualTo(10);
            assertThat(pool(type, "u_minor")).isEqualTo(80);
            assertThat(pool(type, "c_minor")).isEqualTo(20);
        } finally {
            funds.supported = true;
        }
    }

    @Test
    void late_store_recovery_replenishes_b_and_closes_r_without_returning_user_points_again() {
        String type = fixture(false, true);
        addRedemption(type, true);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        confirmations.confirm(successfulCash(type, prepared));
        String receivable = PointsKeys.position("L", type, "AS:" + type);
        var fundsCommand = recoveries.prepare("RECOVER:" + type, receivable, 20);
        assertThat(recoveries.prepare("RECOVER:" + type, receivable, 20)).isEqualTo(fundsCommand);
        assertThatThrownBy(() -> recoveries.prepare("SECOND:" + type, receivable, 1))
                .hasMessage("POINTS_RECOVERY_RECEIVABLE_INSUFFICIENT");
        assertThat(recoveryExecution.execute(fundsCommand.ticketNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.UNKNOWN);
        assertThat(pool(type, "b_minor")).isEqualTo(20);
        assertThat(balance(type, "available_points")).isEqualTo(20);
        assertThat(recoveryExecution.execute(fundsCommand.ticketNo()).confirmedMinor())
                .isEqualTo(20);
        assertThat(recoveryExecution.execute(fundsCommand.ticketNo()).replayed()).isTrue();
        assertThat(pool(type, "b_minor")).isEqualTo(40);
        assertThat(pool(type, "c_minor")).isEqualTo(30);
        assertThat(balance(type, "available_points")).isEqualTo(20);
        var debt =
                jdbc().queryForMap(
                                "SELECT * FROM pts_receivable WHERE receivable_no=?", receivable);
        assertThat(debt.get("state")).isEqualTo("CLOSED");
        assertThat(((Number) debt.get("recovered_minor")).longValue()).isEqualTo(20);
        assertThat(((Number) debt.get("locked_minor")).longValue()).isZero();
        assertThat(funds.sends.get(fundsCommand.ticketNo())).isEqualTo(1);
        assertThat(funds.queries.get(fundsCommand.ticketNo())).isEqualTo(1);
    }

    @Test
    void definite_recovery_failure_unlocks_r_without_creating_cash_and_allows_a_new_intent() {
        String type = fixture(false, true);
        addRedemption(type, true);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        confirmations.confirm(successfulCash(type, prepared));
        String receivable = PointsKeys.position("L", type, "AS:" + type);
        var command = recoveries.prepare("FAILED:" + type, receivable, 20);
        funds.failTickets.add(command.ticketNo());
        assertThat(recoveryExecution.execute(command.ticketNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.FAILED);
        assertThat(recoveryExecution.execute(command.ticketNo()).replayed()).isTrue();
        assertThat(pool(type, "b_minor")).isEqualTo(20);
        var debt =
                jdbc().queryForMap(
                                "SELECT * FROM pts_receivable WHERE receivable_no=?", receivable);
        assertThat(((Number) debt.get("locked_minor")).longValue()).isZero();
        assertThat(((Number) debt.get("recovered_minor")).longValue()).isZero();
        assertThat(recoveries.prepare("RETRY:" + type, receivable, 20).ticketNo())
                .isNotEqualTo(command.ticketNo());
        assertThat(balance(type, "available_points")).isEqualTo(20);
    }

    @Test
    void rejected_collection_revises_before_cash_dispatch_and_platform_waives_missing_points() {
        String type = fixture(true, true);
        posting.post(
                new Voucher(
                        "SPEND:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(move(type, Bucket.AVAILABLE, -20), move(type, Bucket.HELD, 20)),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        var command = command(type, "AS:" + type, 50);
        var prepared = refunds.prepare(command);
        funds.failTickets.add(prepared.collectionTicket());
        assertThat(collections.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.FAILED);
        var revised = collectionDecisions.reviseFailed(prepared.preparationNo());
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(revised.inputHash()).isEqualTo(prepared.inputHash());
        assertThat(revised.cashOffsetMinor()).isZero();
        assertThat(revised.netCashMinor()).isEqualTo(50);
        assertThat(revised.waivedPoints()).isEqualTo(10);
        assertThat(refunds.prepare(command)).isEqualTo(revised);
        assertThat(collectionDecisions.reviseFailed(prepared.preparationNo())).isEqualTo(revised);
        var outcome = confirmations.confirm(successfulCash(type, revised));
        assertThat(outcome.waivedPoints()).isEqualTo(10);
        assertThat(outcome.collectedMinor()).isZero();
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThat(pool(type, "c_minor")).isEqualTo(20);
    }

    @Test
    void unknown_collection_and_existing_cash_intent_cannot_be_revised() {
        String type = fixture(true, true);
        posting.post(
                new Voucher(
                        "SPEND:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(move(type, Bucket.AVAILABLE, -20), move(type, Bucket.HELD, 20)),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        collections.execute(prepared.preparationNo());
        assertThatThrownBy(() -> collectionDecisions.reviseFailed(prepared.preparationNo()))
                .hasMessage("POINTS_REFUND_COLLECTION_NOT_FAILED");
        String failedType = fixture(true, true);
        posting.post(
                new Voucher(
                        "SPEND:" + failedType,
                        failedType,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                move(failedType, Bucket.AVAILABLE, -20),
                                move(failedType, Bucket.HELD, 20)),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        var command = command(failedType, "AS:" + failedType, 50);
        var failed = refunds.prepare(command);
        funds.failTickets.add(failed.collectionTicket());
        collections.execute(failed.preparationNo());
        jdbc().update(
                        "INSERT INTO"
                            + " stl_payment(payment_no,direction,after_sale_no,sub_order_no,user_no,currency,amount_minor,status,pay_channel)"
                            + " VALUES(?,'REFUND',?,?,'USER','CNY',40,'PENDING','TEST')",
                        "CASH:" + failedType,
                        "AS:" + failedType,
                        failedType);
        assertThatThrownBy(() -> collectionDecisions.reviseFailed(failed.preparationNo()))
                .hasMessage("POINTS_REFUND_CASH_ALREADY_PREPARED");
        assertThat(refunds.prepare(command)).isEqualTo(failed);
        assertThat(pool(failedType, "u_minor")).isEqualTo(80);
    }

    @Test
    void cash_authorization_freezes_revision_and_amount_without_moving_funds() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        assertThatCode(
                        () ->
                                cashDecisions.authorize(
                                        prepared.preparationNo(), prepared.inputHash(), 1))
                .doesNotThrowAnyException();
        var decision = cashDecisions.authorize(prepared.preparationNo(), prepared.inputHash(), 1);
        assertThat(decision.netCashMinor()).isEqualTo(50);
        assertThat(decision.cashOffsetMinor()).isZero();
        assertThat(decision.afterSaleNo()).isEqualTo("AS:" + type);
        assertThat(decision.subOrderNo()).isEqualTo(type);
        assertThat(decision.userNo()).isEqualTo("USER");
        assertThat(decision.currency()).isEqualTo("CNY");
        assertThat(decision.channel()).isEqualTo("TEST");
        assertThat(decision.payment().storeNo()).isEqualTo("STORE");
        assertThat(decision.payment().merchantNo()).isEqualTo("MERCHANT");
        assertThat(decision.payment().accountVersion()).isEqualTo("V1");
        assertThat(decision.payment().outTradeNo()).isEqualTo("OUT");
        assertThat(refundState(prepared)).isEqualTo("CASH_AUTHORIZED");
        assertThat(cashDecisions.authorize(prepared.preparationNo(), prepared.inputHash(), 1))
                .isEqualTo(decision);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_voucher WHERE biz_ref=?",
                                        Long.class,
                                        "REFUND_CASH_AUTHORIZE:" + prepared.preparationNo()))
                .isEqualTo(1);
        assertThat(balance(type, "held_points")).isEqualTo(10);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThat(pool(type, "c_minor")).isEqualTo(20);
        assertThatThrownBy(() -> cashDecisions.authorize(prepared.preparationNo(), "wrong-hash", 1))
                .hasMessage("POINTS_REFUND_INPUT_CONFLICT");
        assertThatThrownBy(
                        () ->
                                cashDecisions.authorize(
                                        prepared.preparationNo(), prepared.inputHash(), 2))
                .hasMessage("POINTS_REFUND_REVISION_CONFLICT");
    }

    @Test
    void cash_authorization_waits_for_verified_collection_before_exposing_net_cash() {
        String type = spentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        assertThatThrownBy(
                        () ->
                                cashDecisions.authorize(
                                        prepared.preparationNo(), prepared.inputHash(), 1))
                .hasMessage("POINTS_REFUND_CASH_DECISION_NOT_READY");
        collections.execute(prepared.preparationNo());
        assertThatThrownBy(
                        () ->
                                cashDecisions.authorize(
                                        prepared.preparationNo(), prepared.inputHash(), 1))
                .hasMessage("POINTS_REFUND_CASH_DECISION_NOT_READY");
        collections.execute(prepared.preparationNo());
        var decision = cashDecisions.authorize(prepared.preparationNo(), prepared.inputHash(), 1);
        assertThat(decision.netCashMinor()).isEqualTo(40);
        assertThat(decision.cashOffsetMinor()).isEqualTo(10);
        assertThat(decision.collectionTicket()).isEqualTo(prepared.collectionTicket());
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThat(refundState(prepared)).isEqualTo("CASH_AUTHORIZED");
    }

    @Test
    void cash_authorization_rejects_stale_revision_after_failed_collection_is_waived() {
        String type = spentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        funds.failTickets.add(prepared.collectionTicket());
        collections.execute(prepared.preparationNo());
        assertThatThrownBy(
                        () ->
                                cashDecisions.authorize(
                                        prepared.preparationNo(), prepared.inputHash(), 1))
                .hasMessage("POINTS_REFUND_CASH_DECISION_NOT_READY");
        var revised = collectionDecisions.reviseFailed(prepared.preparationNo());
        assertThatThrownBy(
                        () ->
                                cashDecisions.authorize(
                                        prepared.preparationNo(), prepared.inputHash(), 1))
                .hasMessage("POINTS_REFUND_REVISION_CONFLICT");
        var decision = cashDecisions.authorize(revised.preparationNo(), revised.inputHash(), 2);
        assertThat(decision.revision()).isEqualTo(2);
        assertThat(decision.netCashMinor()).isEqualTo(50);
        assertThat(decision.cashOffsetMinor()).isZero();
        assertThat(collectionDecisions.reviseFailed(prepared.preparationNo())).isEqualTo(revised);
        assertThat(refundState(prepared)).isEqualTo("CASH_AUTHORIZED");
    }

    @Test
    void cash_authorization_is_required_even_when_a_successful_cash_row_already_exists() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var confirmation = successfulCash(type, prepared, false);
        assertThatThrownBy(() -> confirmations.confirm(confirmation))
                .hasMessage("POINTS_REFUND_CASH_AUTHORIZATION_REQUIRED");
        assertThat(balance(type, "held_points")).isEqualTo(10);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        cashDecisions.authorize(prepared.preparationNo(), prepared.inputHash(), 1);
        assertThat(confirmations.confirm(confirmation).state()).isEqualTo("CONFIRMED");
    }

    @Test
    void cash_authorization_does_not_replace_authority_for_zero_cash_completion() {
        String type = fixture(false, true);
        var original = command(type, "AS:" + type, 50);
        var zero =
                new RefundCommand(
                        original.afterSaleNo(),
                        type,
                        "USER",
                        type,
                        "CNY",
                        1,
                        0,
                        50,
                        original.lines(),
                        original.payment());
        var prepared = refunds.prepare(zero);
        assertThat(prepared.netCashMinor()).isZero();
        assertThatThrownBy(
                        () ->
                                cashDecisions.authorize(
                                        prepared.preparationNo(), prepared.inputHash(), 1))
                .hasMessage("POINTS_REFUND_ZERO_CASH_PROOF_REQUIRED");
        assertThat(refundState(prepared)).isEqualTo("PREPARED");
    }

    @Test
    void cash_authorization_has_one_durable_decision_under_repeatable_read_concurrency()
            throws Exception {
        String type = fixture(false, true);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var barrier = new CyclicBarrier(2);
        var transaction = new TransactionTemplate(payTransactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var call =
                    (Callable<PointsRefundCashDecisionService.Decision>)
                            () -> {
                                try {
                                    return transaction.execute(
                                            status -> {
                                                assertThat(refundState(prepared))
                                                        .isEqualTo("PREPARED");
                                                try {
                                                    barrier.await(10, TimeUnit.SECONDS);
                                                } catch (Exception failure) {
                                                    throw new IllegalStateException(failure);
                                                }
                                                return cashDecisions.authorize(
                                                        prepared.preparationNo(),
                                                        prepared.inputHash(),
                                                        1);
                                            });
                                } catch (DataAccessException serializationConflict) {
                                    // Retry only a recognized, fully rolled-back serialization
                                    // conflict in a fresh transaction.
                                    if (!serializationConflict(serializationConflict)) {
                                        throw serializationConflict;
                                    }
                                    return transaction.execute(
                                            status ->
                                                    cashDecisions.authorize(
                                                            prepared.preparationNo(),
                                                            prepared.inputHash(),
                                                            1));
                                }
                            };
            var first = pool.submit(call);
            var second = pool.submit(call);
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(refundState(prepared)).isEqualTo("CASH_AUTHORIZED");
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_voucher WHERE biz_ref=?",
                                        Long.class,
                                        "REFUND_CASH_AUTHORIZE:" + prepared.preparationNo()))
                .isEqualTo(1);
        assertThat(balance(type, "held_points")).isEqualTo(10);
    }

    @Test
    void cash_authorization_cannot_revive_an_aborted_refund_from_an_old_snapshot()
            throws Exception {
        String type = fixture(false, true);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        cashDecisions.authorize(prepared.preparationNo(), prepared.inputHash(), 1);
        String payment = "FAILED_CASH:" + type;
        jdbc().update(
                        "INSERT INTO"
                            + " stl_payment(payment_no,direction,after_sale_no,sub_order_no,user_no,currency,amount_minor,status,pay_channel)"
                            + " VALUES(?,'REFUND',?,?,'USER','CNY',50,'FAILED','TEST')",
                        payment,
                        "AS:" + type,
                        type);
        var transaction = new TransactionTemplate(payTransactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var failure =
                    catchThrowable(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                assertThat(refundState(prepared))
                                                        .isEqualTo("CASH_AUTHORIZED");
                                                var abort =
                                                        pool.submit(
                                                                () ->
                                                                        aborts.abort(
                                                                                new RefundAbort(
                                                                                        prepared
                                                                                                .preparationNo(),
                                                                                        prepared
                                                                                                .inputHash(),
                                                                                        payment)));
                                                try {
                                                    abort.get(10, TimeUnit.SECONDS);
                                                } catch (Exception problem) {
                                                    throw new IllegalStateException(problem);
                                                }
                                                cashDecisions.authorize(
                                                        prepared.preparationNo(),
                                                        prepared.inputHash(),
                                                        1);
                                            }));
            if (serializationConflict(failure)) {
                assertThatThrownBy(
                                () ->
                                        cashDecisions.authorize(
                                                prepared.preparationNo(), prepared.inputHash(), 1))
                        .hasMessage("POINTS_REFUND_CASH_DECISION_NOT_READY");
            } else {
                assertThat(failure)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("POINTS_REFUND_CASH_DECISION_NOT_READY");
            }
        }
        assertThat(refundState(prepared)).isEqualTo("ABORTED");
        assertThat(balance(type, "pending_points")).isEqualTo(20);
        assertThat(balance(type, "held_points")).isZero();
    }

    @Test
    void zero_cash_without_bank_row_uses_a_persisted_source_fact_and_replays_once() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(zeroCommand(type));
        var fact = zeroFact(type, prepared, 0, ZeroCashReason.NO_CASH_DUE);
        assertThatCode(() -> zeroCash.accept(fact)).doesNotThrowAnyException();
        assertThat(refundState(prepared)).isEqualTo("ZERO_AUTHORIZED");
        assertThat(balance(type, "held_points")).isEqualTo(10);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_inbox WHERE event_no=?",
                                        Long.class,
                                        fact.eventNo()))
                .isEqualTo(1);
        var confirmation = zeroConfirmation(prepared, fact);
        var result = confirmations.confirm(confirmation);
        assertThat(result.reclaimedPoints()).isEqualTo(10);
        assertThat(result.state()).isEqualTo("CONFIRMED");
        assertThat(confirmations.confirm(confirmation).replayed()).isTrue();
        zeroCash.accept(fact);
        assertThat(balance(type, "held_points")).isZero();
        assertThat(balance(type, "pending_points")).isEqualTo(10);
        assertThat(pool(type, "u_minor")).isEqualTo(90);
        assertThat(pool(type, "c_minor")).isEqualTo(10);
        assertNoCashRefund(type);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_voucher WHERE biz_ref=?",
                                        Long.class,
                                        "REFUND_ZERO_AUTHORIZE:" + prepared.preparationNo()))
                .isEqualTo(1);
    }

    @Test
    void zero_cash_fact_must_match_the_original_store_revision_and_cash_decision() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(zeroCommand(type));
        var fact = zeroFact(type, prepared, 0, ZeroCashReason.NO_CASH_DUE);
        for (var change :
                List.of(
                        Map.of("storeNo", "OTHER"),
                        Map.of("userNo", "OTHER"),
                        Map.of("afterSaleNo", "OTHER"),
                        Map.of("subOrderNo", "OTHER"),
                        Map.of("typeCode", "OTHER"),
                        Map.of("currency", "KWD"),
                        Map.of("revision", 2),
                        Map.of("sourceVersion", 2),
                        Map.of("inputHash", "WRONG"),
                        Map.of("businessCashMinor", 1),
                        Map.of("cashOffsetMinor", 1),
                        Map.of("netCashMinor", 1))) {
            var invalid = changedFact(fact, change);
            assertThatThrownBy(() -> zeroCash.accept(invalid))
                    .hasMessage("POINTS_ZERO_CASH_FACT_MISMATCH");
        }
        assertThat(refundState(prepared)).isEqualTo("PREPARED");
        assertThat(balance(type, "held_points")).isEqualTo(10);
        assertNoCashRefund(type);
    }

    @Test
    void zero_cash_cannot_authorize_a_positive_refund_or_replace_a_cash_receipt() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var fact = zeroFact(type, prepared, 50, ZeroCashReason.NO_CASH_DUE);
        assertThatThrownBy(() -> zeroCash.accept(fact)).hasMessage("POINTS_ZERO_CASH_NOT_ZERO");
        var cash = successfulCash(type, prepared);
        var mixed =
                new RefundConfirmation(
                        prepared.preparationNo(),
                        prepared.inputHash(),
                        cash.cashRefundPaymentNo(),
                        null,
                        null,
                        fact.eventNo());
        assertThatThrownBy(() -> confirmations.confirm(mixed))
                .hasMessage("POINTS_REFUND_UNEXPECTED_ZERO_CASH_PROOF");
        assertThat(confirmations.confirm(cash).reclaimedPoints()).isEqualTo(10);
    }

    @Test
    void zero_cash_full_offset_waits_for_collection_and_credits_real_collected_money_once() {
        String type = spentGrant();
        var original = command(type, "AS:" + type, 50);
        var command =
                new RefundCommand(
                        original.afterSaleNo(),
                        type,
                        "USER",
                        type,
                        "CNY",
                        1,
                        10,
                        50,
                        original.lines(),
                        original.payment());
        var prepared = refunds.prepare(command);
        assertThat(prepared.netCashMinor()).isZero();
        assertThat(prepared.cashOffsetMinor()).isEqualTo(10);
        var fact = zeroFact(type, prepared, 10, ZeroCashReason.COLLECTED_OFFSET);
        assertThatThrownBy(() -> zeroCash.accept(fact))
                .hasMessage("POINTS_ZERO_CASH_DECISION_NOT_READY");
        collections.execute(prepared.preparationNo());
        assertThatThrownBy(() -> zeroCash.accept(fact))
                .hasMessage("POINTS_ZERO_CASH_DECISION_NOT_READY");
        collections.execute(prepared.preparationNo());
        zeroCash.accept(fact);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        var confirmation = zeroConfirmation(prepared, fact);
        var result = confirmations.confirm(confirmation);
        assertThat(result.collectedMinor()).isEqualTo(10);
        assertThat(result.reclaimedPoints()).isZero();
        assertThat(pool(type, "u_minor")).isEqualTo(90);
        assertThat(pool(type, "c_minor")).isEqualTo(20);
        assertThat(confirmations.confirm(confirmation).replayed()).isTrue();
        assertThat(funds.sends.get(prepared.collectionTicket())).isEqualTo(1);
        assertThat(funds.queries.get(prepared.collectionTicket())).isEqualTo(1);
        assertNoCashRefund(type);
    }

    @Test
    void zero_cash_event_identity_cannot_be_rewritten_or_reused_for_another_preparation() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(zeroCommand(type));
        var fact = zeroFact(type, prepared, 0, ZeroCashReason.NO_CASH_DUE);
        zeroCash.accept(fact);
        assertThatThrownBy(
                        () ->
                                zeroCash.accept(
                                        changedFact(fact, Map.of("eventNo", "different-event"))))
                .hasMessage("POINTS_ZERO_CASH_FACT_CONFLICT");
        String other = fixture(false, true);
        var otherPrepared = refunds.prepare(zeroCommand(other));
        var reused =
                changedFact(
                        zeroFact(other, otherPrepared, 0, ZeroCashReason.NO_CASH_DUE),
                        Map.of("eventNo", fact.eventNo()));
        assertThatThrownBy(() -> zeroCash.accept(reused))
                .hasMessage("POINTS_ZERO_CASH_EVENT_CONFLICT");
        assertThat(refundState(otherPrepared)).isEqualTo("PREPARED");
    }

    @Test
    void zero_cash_confirmation_requires_the_authorized_event_and_no_payment_reference() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(zeroCommand(type));
        var fact = zeroFact(type, prepared, 0, ZeroCashReason.NO_CASH_DUE);
        assertThatThrownBy(() -> confirmations.confirm(zeroConfirmation(prepared, fact)))
                .hasMessage("POINTS_ZERO_CASH_AUTHORIZATION_REQUIRED");
        zeroCash.accept(fact);
        assertThatThrownBy(
                        () ->
                                confirmations.confirm(
                                        new RefundConfirmation(
                                                prepared.preparationNo(),
                                                prepared.inputHash(),
                                                null,
                                                null,
                                                null)))
                .hasMessage("POINTS_ZERO_CASH_PROOF_REQUIRED");
        assertThatThrownBy(
                        () ->
                                confirmations.confirm(
                                        new RefundConfirmation(
                                                prepared.preparationNo(),
                                                prepared.inputHash(),
                                                null,
                                                null,
                                                null,
                                                "foreign-event")))
                .hasMessage("POINTS_ZERO_CASH_PROOF_REQUIRED");
        assertThatThrownBy(
                        () ->
                                confirmations.confirm(
                                        new RefundConfirmation(
                                                prepared.preparationNo(),
                                                prepared.inputHash(),
                                                "FAKE-ZERO-PAYMENT",
                                                null,
                                                null,
                                                fact.eventNo())))
                .hasMessage("POINTS_ZERO_CASH_PAYMENT_NOT_ALLOWED");
        assertThat(confirmations.confirm(zeroConfirmation(prepared, fact)).state())
                .isEqualTo("CONFIRMED");
        assertNoCashRefund(type);
    }

    @Test
    void zero_cash_existing_cash_intent_blocks_authorization_and_fact_writes() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(zeroCommand(type));
        var fact = zeroFact(type, prepared, 0, ZeroCashReason.NO_CASH_DUE);
        jdbc().update(
                        "INSERT INTO"
                            + " stl_payment(payment_no,direction,after_sale_no,sub_order_no,user_no,currency,amount_minor,status,pay_channel)"
                            + " VALUES(?,'REFUND',?,?,'USER','CNY',50,'PENDING','TEST')",
                        "EXISTING:" + type,
                        "AS:" + type,
                        type);
        assertThatThrownBy(() -> zeroCash.accept(fact))
                .hasMessage("POINTS_ZERO_CASH_CASH_INTENT_EXISTS");
        assertThat(refundState(prepared)).isEqualTo("PREPARED");
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_inbox WHERE event_no=?",
                                        Long.class,
                                        fact.eventNo()))
                .isZero();
    }

    @Test
    void zero_cash_fact_and_authorization_rollback_in_the_same_pay_transaction() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(zeroCommand(type));
        var fact = zeroFact(type, prepared, 0, ZeroCashReason.NO_CASH_DUE);
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(payTransactions)
                                        .executeWithoutResult(
                                                status -> {
                                                    zeroCash.accept(fact);
                                                    throw new IllegalStateException(
                                                            "force rollback");
                                                }))
                .hasMessage("force rollback");
        assertThat(refundState(prepared)).isEqualTo("PREPARED");
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_inbox WHERE event_no=?",
                                        Long.class,
                                        fact.eventNo()))
                .isZero();
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_voucher WHERE biz_ref=?",
                                        Long.class,
                                        "REFUND_ZERO_AUTHORIZE:" + prepared.preparationNo()))
                .isZero();
        zeroCash.accept(fact);
        assertThat(confirmations.confirm(zeroConfirmation(prepared, fact)).state())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void
            zero_cash_paid_redemption_returns_funded_points_and_records_store_debt_without_bank_refund() {
        String type = fixture(false, true);
        addRedemption(type, true);
        var prepared = refunds.prepare(zeroCommand(type));
        var fact = zeroFact(type, prepared, 0, ZeroCashReason.NO_CASH_DUE);
        zeroCash.accept(fact);
        var result = confirmations.confirm(zeroConfirmation(prepared, fact));
        assertThat(result.returnedPoints()).isEqualTo(20);
        assertThat(result.reclaimedPoints()).isEqualTo(10);
        assertThat(result.receivableMinor()).isEqualTo(20);
        assertThat(balance(type, "available_points")).isEqualTo(20);
        assertThat(balance(type, "pending_points")).isEqualTo(10);
        assertThat(pool(type, "u_minor")).isEqualTo(50);
        assertThat(pool(type, "c_minor")).isEqualTo(30);
        assertThat(pool(type, "b_minor")).isEqualTo(20);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT original_minor FROM pts_receivable"
                                                + " WHERE type_code=? AND store_no='STORE'",
                                        Long.class,
                                        type))
                .isEqualTo(20);
        assertThat(confirmations.confirm(zeroConfirmation(prepared, fact)).replayed()).isTrue();
        assertThat(pool(type, "b_minor")).isEqualTo(20);
        assertNoCashRefund(type);
    }

    @Test
    void zero_cash_invalid_fact_or_wrong_completion_reason_does_not_change_the_preparation() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(zeroCommand(type));
        var fact = zeroFact(type, prepared, 0, ZeroCashReason.NO_CASH_DUE);
        assertThatThrownBy(() -> zeroCash.accept(null)).hasMessage("POINTS_ZERO_CASH_FACT_INVALID");
        assertThatThrownBy(() -> zeroCash.accept(changedFact(fact, Map.of("eventNo", " "))))
                .hasMessage("POINTS_ZERO_CASH_FACT_INVALID");
        assertThatThrownBy(
                        () ->
                                zeroCash.accept(
                                        changedFact(
                                                fact,
                                                Map.of("occurredAt", "1970-01-01T00:00:00Z"))))
                .hasMessage("POINTS_ZERO_CASH_FACT_INVALID");
        assertThatThrownBy(
                        () ->
                                zeroCash.accept(
                                        changedFact(fact, Map.of("reason", "COLLECTED_OFFSET"))))
                .hasMessage("POINTS_ZERO_CASH_REASON_MISMATCH");
        assertThat(refundState(prepared)).isEqualTo("PREPARED");
        assertThat(balance(type, "held_points")).isEqualTo(10);
    }

    @Test
    void zero_cash_confirmation_cannot_use_a_missing_durable_inbox_fact() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(zeroCommand(type));
        var fact = zeroFact(type, prepared, 0, ZeroCashReason.NO_CASH_DUE);
        zeroCash.accept(fact);
        // Deliberately corrupt only this synthetic fact to prove the confirmer checks persisted
        // evidence.
        jdbc().update("DELETE FROM pts_inbox WHERE event_no=?", fact.eventNo());
        assertThatThrownBy(() -> confirmations.confirm(zeroConfirmation(prepared, fact)))
                .hasMessage("POINTS_ZERO_CASH_PROOF_REQUIRED");
        assertThat(refundState(prepared)).isEqualTo("ZERO_AUTHORIZED");
        assertThat(balance(type, "held_points")).isEqualTo(10);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertNoCashRefund(type);
    }

    @Test
    void zero_cash_confirmation_checks_again_if_a_cash_intent_appears_after_authorization() {
        String type = fixture(false, true);
        var prepared = refunds.prepare(zeroCommand(type));
        var fact = zeroFact(type, prepared, 0, ZeroCashReason.NO_CASH_DUE);
        zeroCash.accept(fact);
        jdbc().update(
                        "INSERT INTO"
                            + " stl_payment(payment_no,direction,after_sale_no,sub_order_no,user_no,currency,amount_minor,status,pay_channel)"
                            + " VALUES(?,'REFUND',?,?,'USER','CNY',50,'PENDING','TEST')",
                        "LATE_CASH:" + type,
                        "AS:" + type,
                        type);
        assertThatThrownBy(() -> confirmations.confirm(zeroConfirmation(prepared, fact)))
                .hasMessage("POINTS_ZERO_CASH_CASH_INTENT_EXISTS");
        assertThat(balance(type, "held_points")).isEqualTo(10);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThat(refundState(prepared)).isEqualTo("ZERO_AUTHORIZED");
    }

    @Test
    void compensation_returns_only_the_collected_cash_before_restoring_original_freezes() {
        String type = partlySpentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var abort = failedCollectedCash(type, prepared);
        var reversal = compensations.prepare(abort, 1);
        assertThat(reversal).isNotNull();
        assertThat(reversal.amountMinor()).isEqualTo(5);
        assertThat(reversal.context().storeNo()).isEqualTo("STORE");
        assertThat(reversal.context().accountVersion()).isEqualTo("V1");
        assertThat(reversal.purpose())
                .isEqualTo(PointsFundsGateway.FundsPurpose.COLLECTION_REVERSAL);
        assertThat(reversal.originalTicketNo()).isEqualTo(prepared.collectionTicket());
        assertThat(reversal.originalEvidenceNo()).isEqualTo("COL:" + prepared.collectionTicket());
        assertThat(compensations.prepare(abort, 1)).isEqualTo(reversal);
        assertThatThrownBy(() -> aborts.abort(abort)).hasMessage("POINTS_REFUND_ABORT_NOT_SAFE");
        assertThat(compensationExecution.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.UNKNOWN);
        assertThat(balance(type, "held_points")).isEqualTo(20);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThatThrownBy(() -> aborts.abort(abort)).hasMessage("POINTS_REFUND_ABORT_NOT_SAFE");
        assertThat(compensationExecution.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.SUCCEEDED);
        assertThat(compensationExecution.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.SUCCEEDED);
        aborts.abort(abort);
        aborts.abort(abort);
        assertThat(balance(type, "held_points")).isEqualTo(15);
        assertThat(balance(type, "available_points")).isEqualTo(5);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThat(pool(type, "c_minor")).isEqualTo(20);
        assertThat(refundState(prepared)).isEqualTo("ABORTED");
        assertThat(funds.sends.get(reversal.ticketNo())).isEqualTo(1);
        assertThat(funds.queries.get(reversal.ticketNo())).isEqualTo(1);
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_fund_evidence WHERE binding_ref=?"
                                                + " AND used_voucher_no IS NOT NULL",
                                        Long.class,
                                        prepared.preparationNo()))
                .isEqualTo(2);
    }

    @Test
    void compensation_rejects_unknown_cash_wrong_revision_and_changed_binding() {
        String type = partlySpentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var abort = failedCollectedCash(type, prepared);
        assertThatThrownBy(() -> compensations.prepare(abort, 2))
                .hasMessage("POINTS_REFUND_REVISION_CONFLICT");
        assertThatThrownBy(
                        () ->
                                compensations.prepare(
                                        new RefundAbort(
                                                prepared.preparationNo(),
                                                "OTHER",
                                                abort.cashRefundPaymentNo()),
                                        1))
                .hasMessage("POINTS_REFUND_INPUT_CONFLICT");
        jdbc().update(
                        "UPDATE stl_payment SET status='PENDING' WHERE payment_no=?",
                        abort.cashRefundPaymentNo());
        assertThatThrownBy(() -> compensations.prepare(abort, 1))
                .hasMessage("POINTS_REFUND_CASH_OUTCOME_UNKNOWN");
        jdbc().update(
                        "UPDATE stl_payment SET status='FAILED',user_no='OTHER' WHERE payment_no=?",
                        abort.cashRefundPaymentNo());
        assertThatThrownBy(() -> compensations.prepare(abort, 1))
                .hasMessage("POINTS_CASH_REFUND_MISMATCH");
        assertThat(refundState(prepared)).isEqualTo("CASH_AUTHORIZED");
        assertThat(balance(type, "held_points")).isEqualTo(20);
    }

    @Test
    void compensation_definite_rejection_keeps_freezes_and_never_resends() {
        String type = partlySpentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var abort = failedCollectedCash(type, prepared);
        var reversal = compensations.prepare(abort, 1);
        assertThat(reversal).isNotNull();
        funds.failTickets.add(reversal.ticketNo());
        assertThat(compensationExecution.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.FAILED);
        assertThat(compensationExecution.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.FAILED);
        assertThatThrownBy(() -> aborts.abort(abort)).hasMessage("POINTS_REFUND_ABORT_NOT_SAFE");
        assertThat(balance(type, "held_points")).isEqualTo(20);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
        assertThat(funds.sends.get(reversal.ticketNo())).isEqualTo(1);
        assertThat(refundState(prepared)).isEqualTo("COMPENSATION_FAILED");
    }

    @Test
    void compensation_requires_a_collected_offset_and_certified_original_reversal() {
        String type = partlySpentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        assertThatThrownBy(
                        () ->
                                compensations.prepare(
                                        new RefundAbort(
                                                prepared.preparationNo(),
                                                prepared.inputHash(),
                                                "ABSENT"),
                                        1))
                .hasMessage("POINTS_REFUND_COMPENSATION_NOT_SAFE");
        var abort = failedCollectedCash(type, prepared);
        funds.reversalSupported = false;
        try {
            assertThatThrownBy(() -> compensations.prepare(abort, 1))
                    .hasMessage("POINTS_REFUND_COMPENSATION_CAPABILITY_UNSUPPORTED");
            assertThat(refundState(prepared)).isEqualTo("CASH_AUTHORIZED");
        } finally {
            funds.reversalSupported = true;
        }
        jdbc().update(
                        "UPDATE stl_payment SET succeeded_at=1 WHERE payment_no=?",
                        abort.cashRefundPaymentNo());
        assertThatThrownBy(() -> compensations.prepare(abort, 1))
                .hasMessage("POINTS_REFUND_CASH_OUTCOME_UNKNOWN");
        String noOffset = fixture(false, true);
        var noOffsetPrepared = refunds.prepare(command(noOffset, "AS:" + noOffset, 50));
        var cash = successfulCash(noOffset, noOffsetPrepared);
        assertThatThrownBy(
                        () ->
                                compensations.prepare(
                                        new RefundAbort(
                                                noOffsetPrepared.preparationNo(),
                                                noOffsetPrepared.inputHash(),
                                                cash.cashRefundPaymentNo()),
                                        1))
                .hasMessage("POINTS_REFUND_COLLECTION_NOT_CONFIRMED");
    }

    @Test
    void compensation_blocks_confirmation_reauthorization_and_rebinding_of_a_frozen_decision() {
        String type = partlySpentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var abort = failedCollectedCash(type, prepared);
        compensations.prepare(abort, 1);
        assertThatThrownBy(
                        () ->
                                compensations.prepare(
                                        new RefundAbort(
                                                prepared.preparationNo(),
                                                prepared.inputHash(),
                                                "OTHER_CASH"),
                                        1))
                .hasMessage("POINTS_REFUND_COMPENSATION_INPUT_CONFLICT");
        assertThatThrownBy(
                        () ->
                                cashDecisions.authorize(
                                        prepared.preparationNo(), prepared.inputHash(), 1))
                .hasMessage("POINTS_REFUND_CASH_DECISION_NOT_READY");
        assertThatThrownBy(
                        () ->
                                confirmations.confirm(
                                        new RefundConfirmation(
                                                prepared.preparationNo(),
                                                prepared.inputHash(),
                                                abort.cashRefundPaymentNo(),
                                                prepared.collectionTicket(),
                                                null)))
                .hasMessage("POINTS_REFUND_CASH_AUTHORIZATION_REQUIRED");
        assertThat(refundState(prepared)).isEqualTo("COMPENSATION_REQUIRED");
        assertThat(balance(type, "held_points")).isEqualTo(20);
    }

    @Test
    void compensation_bad_receipts_and_transport_uncertainty_query_original_without_unlocking() {
        String type = partlySpentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var abort = failedCollectedCash(type, prepared);
        var reversal = compensations.prepare(abort, 1);
        assertThatThrownBy(
                        () ->
                                compensations.accept(
                                        prepared.preparationNo(),
                                        new PointsFundsGateway.FundsResult(
                                                reversal.ticketNo(),
                                                PointsFundsGateway.FundsStatus.SUCCEEDED,
                                                "EARLY",
                                                5,
                                                null)))
                .hasMessage("POINTS_REFUND_COMPENSATION_NOT_DISPATCHED");
        funds.throwTickets.add(reversal.ticketNo());
        assertThat(compensationExecution.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.UNKNOWN);
        funds.throwTickets.remove(reversal.ticketNo());
        funds.receipts.put(
                reversal.ticketNo(),
                new PointsFundsGateway.FundsResult(
                        "WRONG", PointsFundsGateway.FundsStatus.SUCCEEDED, "BAD", 5, null));
        assertThat(compensationExecution.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.UNKNOWN);
        funds.receipts.remove(reversal.ticketNo());
        assertThatThrownBy(() -> aborts.abort(abort)).hasMessage("POINTS_REFUND_ABORT_NOT_SAFE");
        assertThat(compensationExecution.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.SUCCEEDED);
        aborts.abort(abort);
        assertThat(funds.sends.get(reversal.ticketNo())).isEqualTo(1);
        assertThat(funds.queries.get(reversal.ticketNo())).isEqualTo(2);
        assertThat(balance(type, "available_points")).isEqualTo(5);
    }

    @Test
    void compensation_evidence_conflict_rolls_back_result_and_preserves_unconsumed_collection() {
        String type = partlySpentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var abort = failedCollectedCash(type, prepared);
        var reversal = compensations.prepare(abort, 1);
        compensationExecution.execute(prepared.preparationNo());
        posting.recordEvidence(
                new EvidenceRef(
                        "COL:" + reversal.ticketNo(),
                        "CNY",
                        -5,
                        "REFUND_COMPENSATION",
                        "OTHER_PREPARATION"));
        assertThatThrownBy(() -> compensationExecution.execute(prepared.preparationNo()))
                .hasMessage("POINTS_EVIDENCE_CONFLICT");
        assertThat(refundState(prepared)).isEqualTo("COMPENSATION_UNKNOWN");
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT used_voucher_no FROM pts_fund_evidence WHERE"
                                                + " evidence_no=?",
                                        String.class,
                                        "COL:" + prepared.collectionTicket()))
                .isNull();
        assertThat(balance(type, "held_points")).isEqualTo(20);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
    }

    @Test
    void compensation_claim_survives_restart_and_rejects_remote_calls_inside_a_transaction() {
        String type = partlySpentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var abort = failedCollectedCash(type, prepared);
        var reversal = compensations.prepare(abort, 1);
        var tx = new TransactionTemplate(payTransactions);
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        status ->
                                                compensationExecution.execute(
                                                        prepared.preparationNo())))
                .hasMessage("POINTS_FUNDS_REQUIRES_NO_TRANSACTION");
        assertThat(refundState(prepared)).isEqualTo("COMPENSATION_REQUIRED");
        assertThat(compensations.claim(prepared.preparationNo()).submit()).isTrue();
        funds.submit(reversal); // Simulate a remote send whose reply was lost before persistence.
        assertThat(compensations.claim(prepared.preparationNo()).submit()).isFalse();
        assertThat(compensationExecution.execute(prepared.preparationNo()).status())
                .isEqualTo(PointsFundsGateway.FundsStatus.SUCCEEDED);
        aborts.abort(abort);
        assertThat(funds.sends.get(reversal.ticketNo())).isEqualTo(1);
        assertThat(funds.queries.get(reversal.ticketNo())).isEqualTo(1);
    }

    @Test
    void compensation_must_be_supported_before_promising_a_new_refund_collection() {
        String type = partlySpentGrant();
        funds.reversalSupported = false;
        try {
            var prepared = refunds.prepare(command(type, "AS:" + type, 50));
            assertThat(prepared.cashOffsetMinor()).isZero();
            assertThat(prepared.collectionTicket()).isNull();
            assertThat(prepared.netCashMinor()).isEqualTo(50);
            assertThat(prepared.waivedPoints()).isEqualTo(5);
            assertThat(balance(type, "held_points")).isEqualTo(20);
            assertThat(pool(type, "u_minor")).isEqualTo(80);
        } finally {
            funds.reversalSupported = true;
        }
    }

    @Test
    void compensation_result_and_both_evidence_uses_roll_back_with_the_enclosing_pay_transaction() {
        String type = partlySpentGrant();
        var prepared = refunds.prepare(command(type, "AS:" + type, 50));
        var abort = failedCollectedCash(type, prepared);
        var reversal = compensations.prepare(abort, 1);
        compensationExecution.execute(prepared.preparationNo());
        var result =
                new PointsFundsGateway.FundsResult(
                        reversal.ticketNo(),
                        PointsFundsGateway.FundsStatus.SUCCEEDED,
                        "ROLLBACK:" + type,
                        5,
                        null);
        var tx = new TransactionTemplate(payTransactions);
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        status -> {
                                            compensations.accept(prepared.preparationNo(), result);
                                            throw new IllegalStateException("TEST_PAY_ROLLBACK");
                                        }))
                .hasMessage("TEST_PAY_ROLLBACK");
        assertThat(refundState(prepared)).isEqualTo("COMPENSATION_UNKNOWN");
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT used_voucher_no FROM pts_fund_evidence WHERE"
                                                + " evidence_no=?",
                                        String.class,
                                        "COL:" + prepared.collectionTicket()))
                .isNull();
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM pts_fund_evidence WHERE"
                                                + " evidence_no=?",
                                        Long.class,
                                        "ROLLBACK:" + type))
                .isZero();
        assertThat(balance(type, "held_points")).isEqualTo(20);
        compensations.accept(prepared.preparationNo(), result);
        aborts.abort(abort);
        assertThat(balance(type, "available_points")).isEqualTo(5);
        assertThat(pool(type, "u_minor")).isEqualTo(80);
    }

    private String partlySpentGrant() {
        String type = fixture(true, true);
        posting.post(
                new Voucher(
                        "SPEND:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(move(type, Bucket.AVAILABLE, -15), move(type, Bucket.HELD, 15)),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        return type;
    }

    private RefundAbort failedCollectedCash(String type, RefundPreparation prepared) {
        collections.execute(prepared.preparationNo());
        collections.execute(prepared.preparationNo());
        var confirmation = successfulCash(type, prepared);
        jdbc().update(
                        "UPDATE stl_payment SET status='FAILED',trade_no=NULL,succeeded_at=NULL,"
                            + "refund_verified_minor=NULL,refund_verified_at=NULL,refund_verified_provider_no=NULL"
                            + " WHERE payment_no=?",
                        confirmation.cashRefundPaymentNo());
        return new RefundAbort(
                prepared.preparationNo(), prepared.inputHash(), confirmation.cashRefundPaymentNo());
    }

    private RefundCommand zeroCommand(String type) {
        var original = command(type, "AS:" + type, 50);
        return new RefundCommand(
                original.afterSaleNo(),
                type,
                "USER",
                type,
                "CNY",
                1,
                0,
                50,
                original.lines(),
                original.payment());
    }

    private ZeroCashCompletionFact zeroFact(
            String type, RefundPreparation prepared, long business, ZeroCashReason reason) {
        return new ZeroCashCompletionFact(
                "ZERO:" + type,
                prepared.preparationNo(),
                prepared.inputHash(),
                prepared.revision(),
                type,
                "AS:" + type,
                type,
                "USER",
                "STORE",
                "CNY",
                1,
                business,
                prepared.cashOffsetMinor(),
                prepared.netCashMinor(),
                reason,
                Instant.parse("2026-10-15T08:00:00Z"));
    }

    private ZeroCashCompletionFact changedFact(
            ZeroCashCompletionFact fact, Map<String, ?> changes) {
        var mapper = new ObjectMapper();
        ObjectNode tree = mapper.valueToTree(fact);
        changes.forEach((key, value) -> tree.set(key, mapper.valueToTree(value)));
        return mapper.treeToValue(tree, ZeroCashCompletionFact.class);
    }

    private RefundConfirmation zeroConfirmation(
            RefundPreparation prepared, ZeroCashCompletionFact fact) {
        return new RefundConfirmation(
                prepared.preparationNo(),
                prepared.inputHash(),
                null,
                prepared.collectionTicket(),
                null,
                fact.eventNo());
    }

    private void assertNoCashRefund(String type) {
        assertThat(
                        jdbc().queryForObject(
                                        "SELECT COUNT(*) FROM stl_payment WHERE direction='REFUND'"
                                                + " AND after_sale_no=?",
                                        Long.class,
                                        "AS:" + type))
                .isZero();
    }

    private boolean serializationConflict(Throwable failure) {
        if (failure instanceof TransientDataAccessException) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            // MariaDB 11.6.2+ snapshot isolation rolls back a changed-record locking read with
            // ER_CHECKREAD.
            if (cause instanceof SQLException sql
                    && sql.getErrorCode() == 1020
                    && "HY000".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private String refundState(RefundPreparation prepared) {
        return jdbc().queryForObject(
                        "SELECT state FROM pts_refund_preparation WHERE position_no=?",
                        String.class,
                        prepared.preparationNo());
    }

    private String spentGrant() {
        String type = fixture(true, true);
        posting.post(
                new Voucher(
                        "SPEND:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(move(type, Bucket.AVAILABLE, -20), move(type, Bucket.HELD, 20)),
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        "{}"));
        return type;
    }

    private RefundConfirmation successfulCash(String type, RefundPreparation prepared) {
        return successfulCash(type, prepared, true);
    }

    private RefundConfirmation successfulCash(
            String type, RefundPreparation prepared, boolean authorize) {
        if (authorize) {
            cashDecisions.authorize(
                    prepared.preparationNo(), prepared.inputHash(), prepared.revision());
        }
        String payment = "CASH:" + type;
        jdbc().update(
                        "INSERT INTO"
                            + " stl_payment(payment_no,direction,after_sale_no,sub_order_no,user_no,currency,"
                            + "amount_minor,status,pay_channel,trade_no,succeeded_at,refund_verified_minor,refund_verified_at,"
                            + "refund_verified_provider_no)"
                            + " VALUES(?,'REFUND',?,?,'USER','CNY',?,'SUCCESS','TEST','RECEIPT',1,?,1,?)",
                        payment,
                        "AS:" + type,
                        type,
                        prepared.netCashMinor(),
                        prepared.netCashMinor(),
                        "VERIFIED:" + type);
        return new RefundConfirmation(
                prepared.preparationNo(), prepared.inputHash(), payment, null, null);
    }

    private void addRedemption(String type, boolean paid) {
        var json = new ObjectMapper();
        String no = PointsKeys.position("R", type, type);
        var context =
                new PointsFundsGateway.FundsContext(
                        PointsFundsGateway.FundsPath.DIRECT,
                        "TEST",
                        "MERCHANT",
                        "STORE",
                        "V1",
                        "SUB",
                        "TRADE",
                        "OUT",
                        100);
        var agreement =
                new AgreementSnapshot(
                        "AG",
                        type,
                        "MERCHANT",
                        "STORE",
                        "SIGNER",
                        "V1",
                        "HASH",
                        "TERMS",
                        "PER_ORDER",
                        "DIRECT",
                        "TEST",
                        "V1",
                        "ACTIVE");
        var snapshot =
                new RedeemSnapshot(
                        type,
                        "USER",
                        "ORDER:" + type,
                        new Share(type, "MERCHANT", "STORE", 40, 40),
                        new Target(
                                type,
                                "MERCHANT",
                                "STORE",
                                100,
                                "DIRECT",
                                "TEST",
                                "V1",
                                "SUB",
                                "TRADE",
                                List.of(new Item("ITEM", 100))),
                        agreement,
                        context,
                        true);
        var evidence = new EvidenceRef("B:" + type, "CNY", 40);
        posting.recordEvidence(evidence);
        posting.post(
                new Voucher(
                        "B:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(),
                        List.of(new FundMove(FundBucket.B, 40)),
                        List.of(),
                        0,
                        List.of(evidence),
                        "{}"));
        posting.post(
                new Voucher(
                        "PAID_SOURCE:" + type,
                        type,
                        "CNY",
                        new RedeemRate(1, 1),
                        List.of(
                                new PointsMove(
                                        new AccountKey(
                                                type,
                                                HolderType.ISSUE,
                                                "PLATFORM",
                                                Bucket.ISSUABLE),
                                        -40),
                                new PointsMove(
                                        new AccountKey(
                                                type,
                                                HolderType.RECEIVE,
                                                "PLATFORM",
                                                Bucket.REFUND_RESERVED),
                                        40)),
                        List.of(new FundMove(FundBucket.U, -40), new FundMove(FundBucket.D, 40)),
                        List.of(
                                new PositionMove(
                                        PositionMove.Kind.REDEEM,
                                        no,
                                        -1,
                                        "NEW",
                                        "PAYABLE",
                                        40,
                                        0,
                                        json.writeValueAsString(snapshot))),
                        40,
                        List.of(),
                        "{}",
                        List.of(new ReserveMove(no, no, -1, 40, 40, 0, 0))));
        if (paid) {
            var outgoing = new EvidenceRef("OUT:" + type, "CNY", -40);
            posting.recordEvidence(outgoing);
            posting.post(
                    new Voucher(
                            "PAYOUT_FIXTURE:" + type,
                            type,
                            "CNY",
                            new RedeemRate(1, 1),
                            List.of(),
                            List.of(new FundMove(FundBucket.D, -40)),
                            List.of(
                                    new PositionMove(
                                            PositionMove.Kind.REDEEM,
                                            no,
                                            1,
                                            "PAYABLE",
                                            "PAID",
                                            40,
                                            0,
                                            json.writeValueAsString(snapshot))),
                            0,
                            List.of(outgoing),
                            "{}"));
        }
        var fact =
                new LifecycleFact(
                        "PAID:" + type,
                        "HASH",
                        type,
                        "USER",
                        type,
                        1,
                        FactKind.PAID,
                        100,
                        0,
                        List.of(new LineSnapshot("ITEM", 1, 100, 0)),
                        Instant.now());
        var lifecycle = json.createObjectNode();
        lifecycle.set("paidFact", json.valueToTree(fact));
        jdbc().update(
                        "INSERT INTO"
                            + " pts_lifecycle_fact(type_code,state,payload_json,sub_order_no,source_version)"
                            + " VALUES(?,'PAID',?,?,1)",
                        type,
                        json.writeValueAsString(lifecycle),
                        type);
    }

    private String fixture(boolean finalized, boolean funded) {
        return fixture(finalized, funded, 0);
    }

    private String fixture(boolean finalized, boolean funded, long refundedBeforeGrant) {
        String type = "RP" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc().update(
                        "INSERT INTO"
                            + " pts_point_type(type_code,market,currency,rate_points,rate_minor,status)"
                            + " VALUES(?,'CN','CNY',1,1,'ACTIVE')",
                        type);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')", type);
        if (funded) {
            var evidence = new EvidenceRef("E:" + type, "CNY", 100);
            posting.recordEvidence(evidence);
            posting.post(
                    new Voucher(
                            "TOPUP:" + type,
                            type,
                            "CNY",
                            new RedeemRate(1, 1),
                            List.of(
                                    new PointsMove(
                                            new AccountKey(
                                                    type,
                                                    HolderType.MINT,
                                                    "PLATFORM",
                                                    Bucket.ISSUED),
                                            -100),
                                    new PointsMove(
                                            new AccountKey(
                                                    type,
                                                    HolderType.ISSUE,
                                                    "PLATFORM",
                                                    Bucket.ISSUABLE),
                                            100)),
                            List.of(new FundMove(FundBucket.U, 100)),
                            List.of(),
                            0,
                            List.of(evidence),
                            "{}"));
        }
        if (finalized) {
            jdbc().update(
                            "INSERT INTO"
                                + " pts_lifecycle_fact(type_code,state,payload_json,sub_order_no,source_version)"
                                + " VALUES(?,'FINALIZED','{\"finalized\":true}',?,1)",
                            type,
                            type);
        }
        earn.grant(
                new PointsEarnPort.GrantCommand(
                        "EARN:" + type,
                        type,
                        "USER",
                        type,
                        "STORE",
                        "TEST",
                        "APP",
                        1,
                        List.of(
                                new PointsEarnPort.EarnLine(
                                        "ITEM",
                                        "G",
                                        "CAT",
                                        1,
                                        100,
                                        refundedBeforeGrant,
                                        new PointsEarnPort.EarnRule(
                                                PointsEarnPort.RuleMode.FIXED, 20))),
                        "MERCHANT",
                        "APP",
                        new PaymentSnapshot(
                                "DIRECT",
                                "TEST",
                                "MERCHANT",
                                "STORE",
                                "V1",
                                "SUB",
                                "TRADE",
                                "OUT",
                                100)));
        return type;
    }

    private RefundCommand command(String type, String afterSale, long cumulative) {
        return new RefundCommand(
                afterSale,
                type,
                "USER",
                type,
                "CNY",
                1,
                cumulative,
                cumulative,
                List.of(new RefundLine("ITEM", cumulative)),
                new PaymentSnapshot(
                        "DIRECT", "TEST", "MERCHANT", "STORE", "V1", "SUB", "TRADE", "OUT", 100));
    }

    private PointsMove move(String type, Bucket bucket, long amount) {
        return new PointsMove(new AccountKey(type, HolderType.USER, "USER", bucket), amount);
    }

    private long balance(String type, String column) {
        return jdbc().queryForObject(
                        "SELECT "
                                + column
                                + " FROM pts_account WHERE type_code=?"
                                + " AND holder_type='USER'",
                        Long.class,
                        type);
    }

    private long pool(String type, String column) {
        return jdbc().queryForObject(
                        "SELECT " + column + " FROM pts_fund_pool WHERE type_code=?",
                        Long.class,
                        type);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Configuration
    @Import({
        PointsPostingDatabaseTest.Config.class,
        MybatisPointsReadStore.class,
        ai.neargo.shop.pay.points.store.impl.MybatisStatementStore.class,
        MybatisPointsConfigurationStore.class,
        ai.neargo.shop.pay.points.store.impl.MybatisLifecycleStore.class,
        ai.neargo.shop.pay.points.lifecycle.impl.AccountExpiryGuardImpl.class,
        EarnServiceImpl.class,
        MybatisPointsCashRefundReadStore.class,
        MybatisPointsFactStore.class,
        PointsRefundPortImpl.class
    })
    @ComponentScan({
        "ai.neargo.shop.pay.points.refund.impl",
        "ai.neargo.shop.pay.points.app.impl",
        "ai.neargo.shop.pay.points.payout.impl",
        "ai.neargo.shop.pay.points.statement"
    })
    static class Config {
        @Bean
        TestFunds funds() {
            return new TestFunds();
        }
    }

    /** Synthetic lost replies; remote operations assert there is no active database transaction. */
    static class TestFunds implements PointsFundsGateway {
        boolean supported = true;
        boolean reversalSupported = true;
        final Set<String> failTickets = new HashSet<>();
        final Set<String> throwTickets = new HashSet<>();
        final Map<String, FundsResult> receipts = new HashMap<>();
        final Map<String, Integer> sends = new HashMap<>();
        final Map<String, Integer> queries = new HashMap<>();
        final Map<String, FundsCommand> originals = new HashMap<>();

        public FundsCapabilities capabilities(FundsContext context) {
            return new FundsCapabilities(true, true, true, supported, reversalSupported);
        }

        public FundsResult submit(FundsCommand command) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends.merge(command.ticketNo(), 1, Integer::sum);
            originals.put(command.ticketNo(), command);
            if (throwTickets.contains(command.ticketNo())) {
                throw new IllegalStateException("TEST_LOST_REPLY");
            }
            if (failTickets.contains(command.ticketNo())) {
                return new FundsResult(
                        command.ticketNo(),
                        FundsStatus.FAILED,
                        null,
                        command.amountMinor(),
                        "REJECTED");
            }
            return new FundsResult(
                    command.ticketNo(),
                    FundsStatus.UNKNOWN,
                    null,
                    command.amountMinor(),
                    "LOST_REPLY");
        }

        public FundsResult query(FundsCommand command) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(command).isEqualTo(originals.get(command.ticketNo()));
            queries.merge(command.ticketNo(), 1, Integer::sum);
            if (receipts.containsKey(command.ticketNo())) {
                return receipts.get(command.ticketNo());
            }
            return new FundsResult(
                    command.ticketNo(),
                    FundsStatus.SUCCEEDED,
                    "COL:" + command.ticketNo(),
                    command.amountMinor(),
                    null);
        }
    }
}
