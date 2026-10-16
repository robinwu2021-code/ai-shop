package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.statement.StoreStatementService;
import ai.neargo.shop.pay.points.statement.StoreStatementService.Period;
import ai.neargo.shop.pay.points.store.impl.MybatisStatementStore;
import java.sql.Timestamp;
import ai.neargo.shop.pay.points.refund.PointsRefundPreparationService;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsCashRefundReadStore;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundCommand;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundLine;
import ai.neargo.shop.spi.points.PointsRefundPort.PaymentSnapshot;
import ai.neargo.shop.pay.points.payout.StatementPayoutService;
import ai.neargo.shop.pay.points.model.ReceivableMove;
import ai.neargo.shop.pay.points.refund.PointsRefundZeroCashService;
import ai.neargo.shop.pay.points.refund.PointsRefundConfirmationService;
import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashCompletionFact;
import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashReason;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundConfirmation;
import ai.neargo.shop.pay.points.lifecycle.RefundClosureService;
import ai.neargo.shop.spi.points.PointsFactPort.RefundClosureFact;
import ai.neargo.shop.pay.points.store.impl.MybatisLifecycleStore;
import ai.neargo.shop.pay.points.lifecycle.ExpiryService;
import ai.neargo.shop.pay.points.lifecycle.ExpiryExecutionService;
import ai.neargo.shop.pay.points.pool.PointsSwitchService;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Scope;
import ai.neargo.shop.pay.points.pool.impl.PointsSwitchServiceImpl;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.lifecycle.TypeRetirementService;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.pool.impl.PoolRequestServiceImpl.Envelope;

import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.points.app.PointsSettlementHook;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutCommand;
import ai.neargo.shop.spi.points.PointsFactPort.FactKind;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import ai.neargo.shop.spi.points.PointsFactPort.LineSnapshot;
import java.time.Instant;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutTicket;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutOutcome;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.pay.points.earn.impl.EarnServiceImpl;
import ai.neargo.shop.spi.points.PointsEarnPort;
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
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.payout.PayoutSnapshot;
import ai.neargo.shop.pay.points.payout.PointsPayoutService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPath;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsConfigurationStore;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsReadStore;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsFactStore;
import ai.neargo.shop.spi.points.PointsRedeemPort.Share;
import ai.neargo.shop.spi.points.PointsRedeemPort.Target;
import ai.neargo.shop.spi.points.PointsRedeemPort.Item;
import org.junit.jupiter.api.BeforeAll;
import ai.neargo.shop.pay.points.event.PointsEventQueue;
import ai.neargo.shop.pay.points.event.PointsEventWorker;
import ai.neargo.shop.pay.points.event.PointsSourceFactHandler;
import ai.neargo.shop.pay.points.event.impl.PointsFactPortImpl;
import ai.neargo.shop.pay.points.lifecycle.ExpiryReminderService;
import ai.neargo.shop.pay.points.lifecycle.PointsAccountControlService;
import ai.neargo.shop.pay.points.recon.PointsSourceReconciler;
import ai.neargo.shop.pay.points.store.PointsCursorStore;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsCursorStore;
import ai.neargo.shop.spi.points.SourceFactsPort;
import ai.neargo.shop.spi.points.PointsFactPort;
import java.sql.Timestamp;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;
import ai.neargo.shop.spi.notify.UserInboxPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Isolated fixture facts use real pay posting, mapper, and transaction factory without booting the application. */
@SpringJUnitConfig(PointsPayoutPreparationTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties={
        "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_payout_prepare;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
        "shop.points.enabled=true", "spring.datasource.username=${points.jdbc.user:sa}",
        "spring.datasource.password=${points.jdbc.password:}",
        "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
})
class PointsPayoutPreparationTest {
    @Autowired private ApplicationContext context;
    @Autowired private PointsPostingService posting;
    @Autowired private BillMapper bills;
    @Autowired @Qualifier("payDataSource") private DataSource dataSource;
    @Autowired @Qualifier("payTxManager") private PlatformTransactionManager txManager;
    @Autowired private LostReplyFunds testFunds;
    private final ObjectMapper json=new ObjectMapper();

    @BeforeAll
    void schema() {
        PointsPostingDatabaseTest.initializeSchema(dataSource);
        jdbc().execute("CREATE TABLE IF NOT EXISTS stl_payment(payment_no VARCHAR(64) PRIMARY KEY,"
                + "direction VARCHAR(24),after_sale_no VARCHAR(40),deleted TINYINT DEFAULT 0)");
        jdbc().execute("CREATE TABLE IF NOT EXISTS stl_bill (id BIGINT AUTO_INCREMENT PRIMARY KEY,settle_no VARCHAR(64),"
                + "sub_order_no VARCHAR(64),entity_no VARCHAR(64),status VARCHAR(24),tenant_no VARCHAR(32),"
                + "created_at DATETIME,created_by VARCHAR(64),updated_at DATETIME,updated_by VARCHAR(64),"
                + "version BIGINT,deleted TINYINT DEFAULT 0)");
    }
    @Test
    void committed_intent_is_immutable_and_replays_without_spending_d() {
        PayoutCommand command=fixture(true);
        PayoutTicket ticket=begin(command);
        assertThat(begin(command)).isEqualTo(ticket);
        assertThat(ticket.gross()).isEqualTo(30);
        assertThat(ticket.offset()).isZero();
        assertThat(ticket.cash()).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                command.typeCode())).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_redeem_position WHERE position_no=?",String.class,
                command.positionIds().getFirst())).isEqualTo("PAYING");
        assertThat(jdbc().queryForObject("SELECT provider_idem FROM pts_payout_ticket WHERE position_no=?",String.class,
                ticket.ticketNo())).isEqualTo(ticket.providerIdem());
        assertThat(jdbc().queryForObject("SELECT ticket_no FROM pts_redeem_position WHERE position_no=?",String.class,
                command.positionIds().getFirst())).isEqualTo(ticket.ticketNo());
        var changed=new PayoutCommand(command.bizRef(),command.typeCode(),"DIFFERENT",command.positionIds(),
                command.purpose(),command.context(),command.currency());
        assertThatThrownBy(() -> begin(changed)).hasMessage("POINTS_IDEMPOTENCY_CONFLICT");
    }
    @Test
    void incomplete_final_order_snapshot_cannot_enter_payout() {
        PayoutCommand command=fixture(false);
        assertThatThrownBy(() -> begin(command)).hasMessage("POINTS_FINAL_ORDER_SNAPSHOT_REQUIRED");
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_payout_ticket WHERE type_code=?",Long.class,
                command.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT state FROM pts_redeem_position WHERE position_no=?",String.class,
                command.positionIds().getFirst())).isEqualTo("PAYABLE");
    }
    @Test
    void settlement_and_payout_intent_roll_back_in_the_same_pay_transaction() {
        PayoutCommand command=fixture(true);
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            StlBill bill=new StlBill(); bill.setSettleNo("B:"+command.typeCode());
            bill.setSubOrderNo(command.sourceRef()); bill.setEntityNo("M"); bill.setStatus("PENDING");
            bills.insert(bill);
            begin(command);
            status.setRollbackOnly();
        });
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM stl_bill WHERE settle_no=?",Long.class,
                "B:"+command.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_payout_ticket WHERE type_code=?",Long.class,
                command.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT state FROM pts_redeem_position WHERE position_no=?",String.class,
                command.positionIds().getFirst())).isEqualTo("PAYABLE");
    }
    @Test
    void inconsistent_ticket_money_snapshot_rolls_back() {
        PayoutCommand command=fixture(true);
        String no=PointsKeys.position("Y",command.typeCode(),"BAD");
        PayoutTicket ticket=new PayoutTicket(no,30,0,30,"BAD-IDEM",FundsStatus.UNKNOWN);
        FundsCommand wrongAmount=new FundsCommand(no,"BAD-IDEM",command.purpose(),command.context(),"CNY",31);
        var snapshot=new PayoutSnapshot(command,ticket,wrongAmount);
        assertThatThrownBy(() -> posting.post(new Voucher("BAD:"+command.typeCode(),command.typeCode(),"CNY",
                new RedeemRate(1,1),List.of(),List.of(),List.of(new PositionMove(PositionMove.Kind.PAYOUT,no,-1,
                "NEW","READY",30,0,json.writeValueAsString(snapshot))),0,List.of(),"{}")))
                .hasMessage("POINTS_PAYOUT_SNAPSHOT_INVALID");
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_payout_ticket WHERE position_no=?",Long.class,no))
                .isZero();
    }

    @Test
    void unknown_external_success_never_repays() {
        PayoutCommand c=fixture(true);
        PayoutTicket ticket=begin(c);
        assertThat(execute(ticket.ticketNo()).status()).isEqualTo(FundsStatus.UNKNOWN);
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,c.typeCode()))
                .isEqualTo(30);
        assertThat(execute(ticket.ticketNo()).status()).isEqualTo(FundsStatus.SUCCEEDED);
        assertThat(execute(ticket.ticketNo()).replayed()).isTrue();
        assertThat(testFunds.sends.get(ticket.ticketNo())).isEqualTo(1);
        assertThat(testFunds.queries.get(ticket.ticketNo())).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,c.typeCode()))
                .isZero();
        assertThat(jdbc().queryForObject("SELECT state FROM pts_redeem_position WHERE position_no=?",String.class,
                c.positionIds().getFirst())).isEqualTo("PAID");
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_fund_evidence WHERE evidence_no=?"
                + " AND used_voucher_no IS NOT NULL",Long.class,"TEST-PAID:"+ticket.ticketNo())).isEqualTo(1);
    }

    @Test
    void successful_payout_rejects_a_different_evidence_for_the_same_ticket() {
        PayoutCommand c=fixture(true);
        PayoutTicket ticket=begin(c);
        assertThat(execute(ticket.ticketNo()).status()).isEqualTo(FundsStatus.UNKNOWN);
        assertThat(execute(ticket.ticketNo()).status()).isEqualTo(FundsStatus.SUCCEEDED);
        var changed=new FundsResult(ticket.ticketNo(),FundsStatus.SUCCEEDED,"OTHER_EVIDENCE",30,null);
        assertThatThrownBy(() -> context.getBean(PointsPayoutService.class).acceptResult(changed))
                .hasMessage("POINTS_FUNDS_RESULT_CONFLICT");
        var missing=new FundsResult(ticket.ticketNo(),FundsStatus.SUCCEEDED,null,30,null);
        assertThatThrownBy(() -> context.getBean(PointsPayoutService.class).acceptResult(missing))
                .hasMessage("POINTS_FUNDS_RESULT_CONFLICT");
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_fund_evidence WHERE evidence_no=?"
                + " AND used_voucher_no IS NOT NULL",Long.class,"TEST-PAID:"+ticket.ticketNo())).isEqualTo(1);
    }

    @Test
    void definitive_failure_releases_payment_lock_without_debiting_d() {
        PayoutCommand c=fixture(true);
        PayoutTicket ticket=begin(c);
        testFunds.rejections.add(ticket.ticketNo());
        assertThat(execute(ticket.ticketNo()).status()).isEqualTo(FundsStatus.FAILED);
        assertThat(execute(ticket.ticketNo()).replayed()).isTrue();
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,c.typeCode()))
                .isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_redeem_position WHERE position_no=?",String.class,
                c.positionIds().getFirst())).isEqualTo("PAYABLE");
        assertThat(testFunds.sends.get(ticket.ticketNo())).isEqualTo(1);
        assertThat(testFunds.queries.getOrDefault(ticket.ticketNo(),0)).isZero();
    }

    @Test
    void foreign_ticket_receipt_cannot_confirm_another_payout() {
        PayoutCommand c=fixture(true);
        PayoutTicket ticket=begin(c);
        execute(ticket.ticketNo());
        testFunds.corruptReceipts.add(ticket.ticketNo());
        assertThat(execute(ticket.ticketNo()).status()).isEqualTo(FundsStatus.UNKNOWN);
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,c.typeCode()))
                .isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_payout_ticket WHERE position_no=?",String.class,
                ticket.ticketNo())).isEqualTo("UNKNOWN");
    }

    @Test
    void external_result_and_bill_confirmation_roll_back_together_without_resending() {
        PayoutCommand command=fixture(true);
        PayoutTicket ticket=begin(command);
        perform(ticket.ticketNo());
        var result=perform(ticket.ticketNo());
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            StlBill bill=new StlBill(); bill.setSettleNo("CONFIRM:"+command.typeCode());
            bill.setSubOrderNo(command.sourceRef()); bill.setEntityNo("M"); bill.setStatus("SPLIT");
            bills.insert(bill);
            context.getBean(PointsPayoutService.class).acceptResult(result);
            status.setRollbackOnly();
        });
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM stl_bill WHERE settle_no=?",Long.class,
                "CONFIRM:"+command.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                command.typeCode())).isEqualTo(30);
        assertThat(execute(ticket.ticketNo()).status()).isEqualTo(FundsStatus.SUCCEEDED);
        assertThat(testFunds.sends.get(ticket.ticketNo())).isEqualTo(1);
        assertThat(testFunds.queries.get(ticket.ticketNo())).isEqualTo(2);
    }

    @Test
    void active_caller_transaction_cannot_dispatch_funds() {
        PayoutCommand command=fixture(true);
        PayoutTicket ticket=begin(command);
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                assertThatThrownBy(() -> perform(ticket.ticketNo()))
                        .hasMessage("POINTS_FUNDS_REQUIRES_NO_TRANSACTION"));
        assertThat(testFunds.sends.getOrDefault(ticket.ticketNo(),0)).isZero();
        assertThat(jdbc().queryForObject("SELECT state FROM pts_payout_ticket WHERE position_no=?",String.class,
                ticket.ticketNo())).isEqualTo("READY");
    }

    private PointsFundsGateway.FundsResult perform(String ticket) {
        try {
            Class<?> type=Class.forName("ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService");
            Object dispatch=type.getMethod("perform",String.class).invoke(context.getBean(type),ticket);
            return (PointsFundsGateway.FundsResult)dispatch.getClass().getMethod("result").invoke(dispatch);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError("Deferred confirmation missing",e); }
    }

    @Test
    void authoritative_finalization_unlocks_payout_and_replay_does_not_duplicate() {
        PayoutCommand command=fixture(false);
        var fact=finalFact(command, "FINAL:"+command.typeCode(), 2);
        PointsSettlementHook hook=context.getBean(PointsSettlementHook.class);
        hook.onBillFinalized(fact);
        hook.onBillFinalized(fact);
        assertThat(hook.settleModeOf(command.sourceRef())).isEqualTo(PointsSettlementHook.SettleMode.PER_ORDER);
        assertThat(hook.beginPayout(command).gross()).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_inbox WHERE event_no=?",Long.class,
                fact.eventNo())).isEqualTo(1);
    }

    @Test
    void conflicting_final_fact_and_wrong_user_are_rejected_without_writing() {
        PayoutCommand command=fixture(false);
        PointsSettlementHook hook=context.getBean(PointsSettlementHook.class);
        var fact=finalFact(command, "FINAL:"+command.typeCode(), 2);
        hook.onBillFinalized(fact);
        var conflicting=new LifecycleFact(fact.eventNo(),fact.payloadHash(),fact.subOrderNo(),fact.userNo(),
                fact.typeCode(),3,fact.kind(),fact.finalGoodsMinor(),fact.refundedGoodsMinor(),fact.lines(),
                fact.occurredAt());
        assertThatThrownBy(() -> hook.onBillFinalized(conflicting)).hasMessage("POINTS_FACT_CONFLICT");
        var wrongUser=new LifecycleFact("WRONG:"+command.typeCode(),"HASH",fact.subOrderNo(),"OTHER",
                fact.typeCode(),3,fact.kind(),fact.finalGoodsMinor(),fact.refundedGoodsMinor(),fact.lines(),
                fact.occurredAt());
        assertThatThrownBy(() -> hook.onBillFinalized(wrongUser)).hasMessage("POINTS_FACT_SUBJECT_MISMATCH");
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_inbox WHERE event_no=?",Long.class,
                wrongUser.eventNo())).isZero();
    }

    @Test
    void finalization_activates_remaining_pending_grant_once() {
        PayoutCommand command=fixture(true);
        addEarnBudget(command);
        context.getBean(EarnService.class).grant(earnCommand(command));
        long expiry=jdbc().queryForObject("SELECT expiry_revision FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER' AND holder_no='U'",Long.class,command.typeCode());
        PointsSettlementHook hook=context.getBean(PointsSettlementHook.class);
        var fact=finalFact(command,"FINAL:"+command.typeCode(),2);
        hook.onBillFinalized(fact);
        hook.onBillFinalized(fact);
        assertThat(jdbc().queryForObject("SELECT pending_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER' AND holder_no='U'",Long.class,command.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT available_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER' AND holder_no='U'",Long.class,command.typeCode())).isEqualTo(90);
        assertThat(jdbc().queryForObject("SELECT expiry_revision FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER' AND holder_no='U'",Long.class,command.typeCode())).isEqualTo(expiry);
    }

    @Test
    void late_grant_after_finalization_goes_directly_to_available() {
        PayoutCommand command=fixture(true);
        context.getBean(PointsSettlementHook.class).onBillFinalized(finalFact(command,"FINAL:"+command.typeCode(),2));
        addEarnBudget(command);
        assertThat(context.getBean(EarnService.class).grant(earnCommand(command)).grantedPoints()).isEqualTo(20);
        assertThat(jdbc().queryForObject("SELECT pending_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER' AND holder_no='U'",Long.class,command.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT available_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER' AND holder_no='U'",Long.class,command.typeCode())).isEqualTo(90);
    }

    private PointsEarnPort.GrantCommand earnCommand(PayoutCommand c) {
        return new PointsEarnPort.GrantCommand("EARN:"+c.typeCode(),c.sourceRef(),"U",c.typeCode(),"STORE","TEST","MP",1,
                List.of(new PointsEarnPort.EarnLine("I","G","C",1,100,0,
                        new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.FIXED,20))));
    }
    private void addEarnBudget(PayoutCommand c) {
        String evidence="EXTRA:"+c.typeCode();
        posting.recordEvidence(new EvidenceRef(evidence,"CNY",20));
        posting.post(new Voucher("BUDGET:"+c.typeCode(),c.typeCode(),"CNY",new RedeemRate(1,1),List.of(
                move(c.typeCode(),HolderType.MINT,"PLATFORM",Bucket.ISSUED,-20),
                move(c.typeCode(),HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,20)),
                List.of(new FundMove(FundBucket.U,20)),List.of(),0,List.of(new EvidenceRef(evidence,"CNY",20)),"{}"));
    }

    @Test
    void paid_fact_moves_per_order_hold_to_payable_and_keeps_b_reserved() {
        PayoutCommand command=fixture(false,false,false);
        var fact=paidFact(command);
        paid(fact);
        paid(fact);
        assertThat(jdbc().queryForObject("SELECT held_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER'",Long.class,command.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                command.typeCode())).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT reserved_b_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                command.typeCode())).isEqualTo(30);
        assertThat(begin(command).gross()).isEqualTo(30);
    }

    @Test
    void periodic_paid_fact_stays_backed_in_store_until_finalization() {
        PayoutCommand command=fixture(false,false,true);
        paid(paidFact(command));
        assertThat(jdbc().queryForObject("SELECT pending_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='STORE' AND holder_no='STORE'",Long.class,command.typeCode())).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                command.typeCode())).isZero();
        context.getBean(PointsSettlementHook.class).onBillFinalized(finalFact(command,"FINAL:"+command.typeCode(),2));
        assertThat(jdbc().queryForObject("SELECT available_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='STORE' AND holder_no='STORE'",Long.class,command.typeCode())).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT pending_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='STORE' AND holder_no='STORE'",Long.class,command.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT state FROM pts_redeem_position WHERE position_no=?",String.class,
                command.positionIds().getFirst())).isEqualTo("SETTLEABLE");
    }

    @Test
    void paid_version_cannot_hide_an_independent_finalization_version() {
        PayoutCommand command=fixture(false,false,true);
        var paid=paidFact(command);
        paid(new LifecycleFact(paid.eventNo(),paid.payloadHash(),paid.subOrderNo(),paid.userNo(),paid.typeCode(),
                100,paid.kind(),paid.finalGoodsMinor(),paid.refundedGoodsMinor(),paid.lines(),paid.occurredAt()));
        context.getBean(PointsSettlementHook.class).onBillFinalized(finalFact(command,"FINAL:"+command.typeCode(),2));
        assertThat(jdbc().queryForObject("SELECT available_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='STORE' AND holder_no='STORE'",Long.class,command.typeCode())).isEqualTo(30);
    }

    private LifecycleFact paidFact(PayoutCommand command) {
        var finalFact=finalFact(command,"PAID:"+command.typeCode(),1);
        return new LifecycleFact(finalFact.eventNo(),finalFact.payloadHash(),finalFact.subOrderNo(),finalFact.userNo(),
                finalFact.typeCode(),finalFact.sourceVersion(),FactKind.PAID,finalFact.finalGoodsMinor(),
                finalFact.refundedGoodsMinor(),finalFact.lines(),finalFact.occurredAt());
    }
    private void paid(LifecycleFact fact) {
        try {
            Class<?> type=Class.forName("ai.neargo.shop.pay.points.app.PointsPaidFactService");
            type.getMethod("onPaid",LifecycleFact.class).invoke(context.getBean(type),fact);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError("Paid fact entry missing",e); }
    }

    private LifecycleFact finalFact(PayoutCommand c,String event,long version) {
        return new LifecycleFact(event,"HASH",c.sourceRef(),"U",c.typeCode(),version,FactKind.FINALIZED,
                100,0,List.of(new LineSnapshot("I",1,100,0)),Instant.parse("2026-10-14T00:00:00Z"));
    }

    private PayoutOutcome execute(String ticket) {
        try {
            Class<?> type=Class.forName("ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService");
            return (PayoutOutcome)type.getMethod("execute",String.class).invoke(context.getBean(type),ticket);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError("Payout execution service missing",e); }
    }

    @Test void statement_draft_fixes_candidates_and_confirmation_moves_store_backing_once() {
        var c=periodicStatementFixture();
        var statements=context.getBean(StoreStatementService.class);
        var period=new Period(c.typeCode(),"STORE",0,4102444800000L);
        var draft=statements.draft(period,"OPERATOR");
        assertThat(draft).isNotNull();
        assertThat(draft.positionIds()).containsExactlyElementsOf(c.positionIds());
        assertThat(draft.current().points()).isEqualTo(30);
        assertThat(statements.draft(period,"OPERATOR")).isEqualTo(draft);
        var confirmed=statements.confirm(draft.statementNo(),1,"REVIEWER");
        assertThat(confirmed.state()).isEqualTo("CONFIRMED");
        assertThat(statements.confirm(draft.statementNo(),1,"REVIEWER")).isEqualTo(confirmed);
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT available_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='STORE'",Long.class,c.typeCode())).isZero();
    }

    @Test void statement_revisions_and_disputes_prevent_stale_confirmation() {
        var c=periodicStatementFixture();
        var statements=context.getBean(StoreStatementService.class);
        var draft=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OPERATOR");
        assertThat(draft).isNotNull();
        statements.dispute(draft.statementNo(),1,"STORE_REVIEWER","CHECK_AMOUNT");
        assertThatThrownBy(() -> statements.confirm(draft.statementNo(),1,"REVIEWER"))
                .hasMessage("POINTS_STATEMENT_NOT_DRAFT");
        var revised=statements.redraft(draft.statementNo(),1,"OPERATOR");
        assertThat(revised.current().number()).isEqualTo(2);
        assertThat(revised.revisions()).hasSize(2);
        assertThat(revised.positionIds()).isEqualTo(draft.positionIds());
        assertThatThrownBy(() -> statements.confirm(draft.statementNo(),1,"REVIEWER"))
                .hasMessage("POINTS_STATEMENT_REVISION_CONFLICT");
        assertThat(statements.confirm(draft.statementNo(),2,"REVIEWER").state()).isEqualTo("CONFIRMED");
    }

    @Test void statement_period_binding_rejects_overlapping_source_reuse_and_changed_period_end() {
        var c=periodicStatementFixture();
        var statements=context.getBean(StoreStatementService.class);
        var draft=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OPERATOR");
        assertThat(draft).isNotNull();
        assertThatThrownBy(() -> statements.draft(new Period(c.typeCode(),"STORE",0,4102444800001L),"OPERATOR"))
                .hasMessage("POINTS_STATEMENT_PERIOD_CONFLICT");
        assertThatThrownBy(() -> statements.draft(new Period(c.typeCode(),"STORE",1,4102444800000L),"OPERATOR"))
                .hasMessage("POINTS_STATEMENT_EMPTY");
        assertThat(jdbc().queryForObject("SELECT statement_no FROM pts_redeem_position WHERE position_no=?",
                String.class,c.positionIds().getFirst())).isEqualTo(draft.statementNo());
    }

    @Test void statement_period_uses_the_final_fact_time_and_a_half_open_interval() {
        var c=periodicStatementFixture();
        var statements=context.getBean(StoreStatementService.class);
        long finalizedAt=Instant.parse("2026-10-14T00:00:00Z").toEpochMilli();
        jdbc().update("UPDATE pts_redeem_position SET created_at=? WHERE position_no=?",
                Timestamp.from(Instant.parse("2026-10-16T00:00:00Z")),c.positionIds().getFirst());
        assertThatThrownBy(() -> statements.draft(new Period(c.typeCode(),"STORE",finalizedAt-1,finalizedAt),"OP"))
                .hasMessage("POINTS_STATEMENT_EMPTY");
        assertThatCode(() -> statements.draft(new Period(c.typeCode(),"STORE",finalizedAt,finalizedAt+1),"OP"))
                .doesNotThrowAnyException();
    }

    @Test void statement_source_change_requires_redraft_without_replacing_the_original_revision() {
        var c=periodicStatementFixture();
        var statements=context.getBean(StoreStatementService.class);
        var first=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        // Inject a new source revision in this isolated fixture; confirmation must observe the change.
        jdbc().update("UPDATE pts_redeem_position SET version=version+1 WHERE position_no=?",
                c.positionIds().getFirst());
        assertThatThrownBy(() -> statements.confirm(first.statementNo(),1,"REVIEWER"))
                .hasMessage("POINTS_STATEMENT_REDRAFT_REQUIRED");
        var second=statements.redraft(first.statementNo(),1,"OP");
        assertThat(second.revisions().getFirst()).isEqualTo(first.current());
        assertThat(second.current().lines().getFirst().sourceVersion())
                .isEqualTo(first.current().lines().getFirst().sourceVersion()+1);
        assertThat(statements.confirm(first.statementNo(),2,"REVIEWER").state()).isEqualTo("CONFIRMED");
    }

    @Test void statement_confirmation_and_financial_moves_roll_back_as_one_pay_transaction() {
        var c=periodicStatementFixture();
        var statements=context.getBean(StoreStatementService.class);
        var draft=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            statements.confirm(draft.statementNo(),1,"REVIEWER");
            status.setRollbackOnly();
        });
        assertThat(jdbc().queryForObject("SELECT state FROM pts_store_statement WHERE position_no=?",
                String.class,draft.statementNo())).isEqualTo("DRAFT");
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",
                Long.class,c.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT available_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='STORE'",Long.class,c.typeCode())).isEqualTo(30);
        assertThat(statements.confirm(draft.statementNo(),1,"REVIEWER").state()).isEqualTo("CONFIRMED");
    }

    @Test void statement_refund_reopens_unpaid_confirmation_before_freezing_the_source() {
        var c=periodicStatementFixture();
        var statements=context.getBean(StoreStatementService.class);
        var draft=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        statements.confirm(draft.statementNo(),1,"REVIEWER");
        var refund=new RefundCommand("AS:"+c.typeCode(),c.sourceRef(),"U",c.typeCode(),"CNY",1,50,50,
                List.of(new RefundLine("I",50)),new PaymentSnapshot("AGGREGATED","TEST","M","STORE","A1",
                "SM","TRADE",c.sourceRef(),100));
        context.getBean(PointsRefundPreparationService.class).prepare(refund);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_store_statement WHERE position_no=?",
                String.class,draft.statementNo())).isEqualTo("DRAFT");
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT available_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='STORE'",Long.class,c.typeCode())).isEqualTo(30);
        assertThatThrownBy(() -> statements.confirm(draft.statementNo(),1,"REVIEWER"))
                .hasMessage("POINTS_STATEMENT_REVISION_CONFLICT");
        assertThatThrownBy(() -> statements.redraft(draft.statementNo(),2,"OP"))
                .hasMessage("POINTS_STATEMENT_SOURCE_NOT_READY");
    }

    @Test void statement_payout_offsets_actual_store_debt_only_after_cash_is_confirmed() {
        var c=periodicStatementFixture(100);
        seedDebt(c,30);
        var statements=context.getBean(StoreStatementService.class);
        var statement=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        statements.confirm(statement.statementNo(),1,"REVIEWER");
        var payout=context.getBean(StatementPayoutService.class).prepare("PERIOD:"+c.typeCode(),
                statement.statementNo(),1,"OP");
        assertThat(payout).isNotNull();
        assertThat(payout.gross()).isEqualTo(100);
        assertThat(payout.offset()).isEqualTo(30);
        assertThat(payout.cash()).isEqualTo(70);
        assertThat(execute(payout.ticketNo()).status()).isEqualTo(FundsStatus.UNKNOWN);
        assertThat(jdbc().queryForObject("SELECT recovered_minor FROM pts_receivable WHERE type_code=?",
                Long.class,c.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT locked_minor FROM pts_receivable WHERE type_code=?",
                Long.class,c.typeCode())).isEqualTo(30);
        assertThat(execute(payout.ticketNo()).confirmedMinor()).isEqualTo(100);
        assertThat(execute(payout.ticketNo()).replayed()).isTrue();
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT b_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(200);
        assertThat(jdbc().queryForObject("SELECT recovered_minor FROM pts_receivable WHERE type_code=?",
                Long.class,c.typeCode())).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_store_statement WHERE position_no=?",
                String.class,statement.statementNo())).isEqualTo("PAID");
        assertThat(testFunds.sends.get(payout.ticketNo())).isEqualTo(1);
    }

    @Test void statement_full_offset_uses_no_gateway_and_leaves_excess_debt_open() {
        var c=periodicStatementFixture(30);
        seedDebt(c,40);
        var statements=context.getBean(StoreStatementService.class);
        var statement=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        statements.confirm(statement.statementNo(),1,"REVIEWER");
        var payout=context.getBean(StatementPayoutService.class).prepare("PERIOD:"+c.typeCode(),
                statement.statementNo(),1,"OP");
        assertThat(payout).isNotNull();
        assertThat(payout.cash()).isZero();
        assertThat(execute(payout.ticketNo()).confirmedMinor()).isEqualTo(30);
        assertThat(testFunds.sends).doesNotContainKey(payout.ticketNo());
        assertThat(testFunds.queries).doesNotContainKey(payout.ticketNo());
        assertThat(jdbc().queryForObject("SELECT recovered_minor FROM pts_receivable WHERE type_code=?",
                Long.class,c.typeCode())).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_receivable WHERE type_code=?",
                String.class,c.typeCode())).isEqualTo("OPEN");
    }

    @Test void statement_failed_payout_releases_offset_locks_and_retains_the_original_intent() {
        var c=periodicStatementFixture(100);
        seedDebt(c,30);
        var statements=context.getBean(StoreStatementService.class);
        var statement=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        statements.confirm(statement.statementNo(),1,"REVIEWER");
        var payouts=context.getBean(StatementPayoutService.class);
        var payout=payouts.prepare("PERIOD:"+c.typeCode(),statement.statementNo(),1,"OP");
        assertThat(payout).isNotNull();
        testFunds.rejections.add(payout.ticketNo());
        assertThat(execute(payout.ticketNo()).status()).isEqualTo(FundsStatus.FAILED);
        assertThat(jdbc().queryForObject("SELECT locked_minor FROM pts_receivable WHERE type_code=?",
                Long.class,c.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",
                Long.class,c.typeCode())).isEqualTo(100);
        assertThat(payouts.prepare("PERIOD:"+c.typeCode(),statement.statementNo(),1,"OP")).isEqualTo(payout);
        assertThat(payouts.prepare("RETRY:"+c.typeCode(),statement.statementNo(),1,"OP").ticketNo())
                .isNotEqualTo(payout.ticketNo());
    }

    @Test void statement_partial_refund_recalculates_and_reconfirms_only_the_remaining_points() {
        var c=periodicStatementFixture();
        var statements=context.getBean(StoreStatementService.class);
        var statement=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        statements.confirm(statement.statementNo(),1,"REVIEWER");
        completeZeroRefund(c,"PARTIAL",50);
        var revised=statements.redraft(statement.statementNo(),2,"OP");
        assertThat(revised.current().points()).isEqualTo(15);
        statements.confirm(statement.statementNo(),3,"REVIEWER");
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(15);
        assertThat(jdbc().queryForObject("SELECT available_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER'",Long.class,c.typeCode())).isEqualTo(85);
    }

    @Test void statement_paid_refund_uses_buffer_and_opens_store_receivable_without_reopening_paid_bill() {
        var c=periodicStatementFixture(100);
        var statements=context.getBean(StoreStatementService.class);
        var statement=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        statements.confirm(statement.statementNo(),1,"REVIEWER");
        var ticket=context.getBean(StatementPayoutService.class).prepare("PERIOD:"+c.typeCode(),
                statement.statementNo(),1,"OP");
        execute(ticket.ticketNo()); execute(ticket.ticketNo());
        assertThat(statements.confirm(statement.statementNo(),1,"REVIEWER").state()).isEqualTo("PAID");
        completeZeroRefund(c,"PAID_REFUND",50);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_store_statement WHERE position_no=?",
                String.class,statement.statementNo())).isEqualTo("PAID");
        assertThat(jdbc().queryForObject("SELECT b_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(150);
        assertThat(jdbc().queryForObject("SELECT c_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(50);
        assertThat(jdbc().queryForObject("SELECT original_minor FROM pts_receivable WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(50);
    }

    @Test void statement_carries_unbound_late_facts_to_the_next_period_once() {
        var c=periodicStatementFixture();
        long at=Instant.parse("2026-10-14T00:00:00Z").toEpochMilli();
        var statements=context.getBean(StoreStatementService.class);
        assertThatCode(() -> statements.draft(new Period(c.typeCode(),"STORE",at+1,at+2),"OP"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> statements.draft(new Period(c.typeCode(),"STORE",at+2,at+3),"OP"))
                .hasMessage("POINTS_STATEMENT_EMPTY");
    }

    @Test void statement_full_unpaid_refund_clears_the_zero_value_bill() {
        var c=periodicStatementFixture();
        var statements=context.getBean(StoreStatementService.class);
        var statement=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        statements.confirm(statement.statementNo(),1,"REVIEWER");
        completeZeroRefund(c,"FULL",100);
        var revised=statements.redraft(statement.statementNo(),2,"OP");
        assertThat(revised.state()).isEqualTo("CLEARED");
        assertThat(revised.current().points()).isZero();
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isZero();
    }

    @Test void statement_unknown_payment_blocks_refund_and_preserves_all_offset_reservations() {
        var c=periodicStatementFixture(100);
        seedDebt(c,30);
        var statements=context.getBean(StoreStatementService.class);
        var statement=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        statements.confirm(statement.statementNo(),1,"REVIEWER");
        var ticket=context.getBean(StatementPayoutService.class).prepare("PERIOD:"+c.typeCode(),
                statement.statementNo(),1,"OP");
        execute(ticket.ticketNo());
        assertThatThrownBy(() -> completeZeroRefund(c,"UNKNOWN",50))
                .hasMessage("POINTS_STATEMENT_PAYMENT_UNRESOLVED");
        assertThat(jdbc().queryForObject("SELECT locked_minor FROM pts_receivable WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(100);
    }

    @Test void statement_two_tickets_cannot_offset_the_same_receivable_twice() {
        var c=periodicStatementFixture();
        var statements=context.getBean(StoreStatementService.class);
        var first=statements.draft(new Period(c.typeCode(),"STORE",0,4102444800000L),"OP");
        var secondSource=addPeriodicSource(c);
        var second=statements.draft(new Period(c.typeCode(),"STORE",1,4102444800000L),"OP");
        assertThat(second.positionIds()).containsExactlyElementsOf(secondSource.positionIds());
        seedDebt(c,40);
        statements.confirm(first.statementNo(),1,"REVIEWER");
        statements.confirm(second.statementNo(),1,"REVIEWER");
        var payouts=context.getBean(StatementPayoutService.class);
        var firstTicket=payouts.prepare("FIRST:"+c.typeCode(),first.statementNo(),1,"OP");
        var secondTicket=payouts.prepare("SECOND:"+c.typeCode(),second.statementNo(),1,"OP");
        assertThat(firstTicket.offset()).isEqualTo(30);
        assertThat(secondTicket.offset()).isEqualTo(10);
        assertThat(secondTicket.cash()).isEqualTo(20);
        assertThat(jdbc().queryForObject("SELECT locked_minor FROM pts_receivable WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(40);
        execute(firstTicket.ticketNo()); execute(secondTicket.ticketNo()); execute(secondTicket.ticketNo());
        assertThat(jdbc().queryForObject("SELECT recovered_minor FROM pts_receivable WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(40);
        assertThat(jdbc().queryForObject("SELECT locked_minor FROM pts_receivable WHERE type_code=?",Long.class,
                c.typeCode())).isZero();
    }

    private PayoutCommand addPeriodicSource(PayoutCommand first) {
        String type=first.typeCode();
        String sub="SECOND:"+type;
        String no=PointsKeys.position("R",type,sub);
        var original=json.readValue(jdbc().queryForObject("SELECT payload_json FROM pts_redeem_position"
                + " WHERE position_no=?",String.class,first.positionIds().getFirst()),RedeemSnapshot.class);
        var route=new FundsContext(FundsPath.AGGREGATED,"TEST","M","STORE","A1","SM","TRADE",sub,100);
        var snapshot=new RedeemSnapshot(sub,"U","ORDER2:"+type,new Share(sub,"M","STORE",30,30),
                new Target(sub,"M","STORE",100,"AGGREGATED","TEST","A1","SM","TRADE",
                        List.of(new Item("ITEM",100))),original.agreement(),route,false);
        posting.post(new Voucher("SECOND_HOLD:"+type,type,"CNY",new RedeemRate(1,1),List.of(
                move(type,HolderType.USER,"U",Bucket.AVAILABLE,-30),move(type,HolderType.USER,"U",Bucket.HELD,30)),
                List.of(),List.of(new PositionMove(PositionMove.Kind.REDEEM,no,-1,"NEW","HELD",30,0,
                        json.writeValueAsString(snapshot))),30,List.of(),"{}",
                List.of(new ReserveMove(no,no,-1,30,30,0,0))));
        var c=new PayoutCommand("SECOND_PAY:"+type,type,sub,List.of(no),FundsPurpose.INTERNAL_REPLENISHMENT,route,"CNY");
        var paid=paidFact(c);
        paid(new LifecycleFact("SECOND_PAID:"+type,paid.payloadHash(),paid.subOrderNo(),paid.userNo(),paid.typeCode(),
                paid.sourceVersion(),paid.kind(),paid.finalGoodsMinor(),paid.refundedGoodsMinor(),paid.lines(),
                paid.occurredAt()));
        context.getBean(PointsSettlementHook.class).onBillFinalized(finalFact(c,"SECOND_FINAL:"+type,2));
        return c;
    }

    @Test void closure_releases_only_remaining_paid_responsibility_and_blocks_future_refunds() {
        var c=fixture(false,false,false);
        paid(paidFact(c));
        context.getBean(PointsSettlementHook.class).onBillFinalized(finalFact(c,"FINAL:"+c.typeCode(),2));
        var ticket=begin(c); execute(ticket.ticketNo()); execute(ticket.ticketNo());
        var closure=context.getBean(RefundClosureService.class);
        var proof=closureFact(c);
        var result=closure.close(proof);
        assertThat(result).isNotNull();
        assertThat(result.releasedPoints()).isEqualTo(30);
        assertThat(result.releasedMinor()).isEqualTo(30);
        assertThat(closure.close(proof).replayed()).isTrue();
        assertThat(jdbc().queryForObject("SELECT reserved_b_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT recirculatable_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='RECEIVE'",Long.class,c.typeCode())).isEqualTo(30);
        assertThatThrownBy(() -> completeZeroRefund(c,"TOO_LATE",50))
                .hasMessage("POINTS_REFUND_RESPONSIBILITY_CLOSED");
    }

    @Test void closure_rejects_unpaid_or_open_refund_responsibility_without_releasing_backing() {
        var c=periodicStatementFixture();
        var closure=context.getBean(RefundClosureService.class);
        assertThatThrownBy(() -> closure.close(closureFact(c))).hasMessage("POINTS_CLOSURE_FUNDS_NOT_FINAL");
        var proof=closureFact(c);
        var unsafe=new RefundClosureFact(proof.eventNo(),proof.typeCode(),proof.subOrderNo(),proof.userNo(),
                proof.sourceVersion(),proof.windowClosedAt(),false,true,proof.occurredAt());
        assertThatThrownBy(() -> closure.close(unsafe)).hasMessage("POINTS_CLOSURE_PROOF_INVALID");
        assertThat(jdbc().queryForObject("SELECT reserved_b_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(30);
    }

    private RefundClosureFact closureFact(PayoutCommand c) {
        return new RefundClosureFact("CLOSE:"+c.sourceRef(),c.typeCode(),c.sourceRef(),"U",3,
                Instant.parse("2026-10-15T00:00:00Z"),true,true,Instant.parse("2026-10-16T00:00:00Z"));
    }

    @Test void expiry_holds_only_available_points_and_waits_for_actual_income_before_releasing_c() {
        var c=expiryFixture();
        var expiry=context.getBean(ExpiryService.class);
        var request=expiryRequest(c,7,2000);
        var command=expiry.prepare(request);
        assertThat(command).isNotNull();
        assertThat(command.amountMinor()).isEqualTo(70);
        assertThat(expiry.prepare(request)).isEqualTo(command);
        assertThat(jdbc().queryForObject("SELECT held_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER'",Long.class,c.typeCode())).isEqualTo(100);
        var execution=context.getBean(ExpiryExecutionService.class);
        assertThat(execution.execute(command.ticketNo()).status()).isEqualTo(FundsStatus.UNKNOWN);
        assertThat(jdbc().queryForObject("SELECT c_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(100);
        assertThat(execution.execute(command.ticketNo()).status()).isEqualTo(FundsStatus.SUCCEEDED);
        execution.execute(command.ticketNo());
        assertThat(jdbc().queryForObject("SELECT c_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT held_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER'",Long.class,c.typeCode())).isEqualTo(30);
        assertThat(jdbc().queryForObject("SELECT recirculatable_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='RECEIVE'",Long.class,c.typeCode())).isEqualTo(70);
        assertThat(testFunds.sends.get(command.ticketNo())).isEqualTo(1);
    }

    @Test void expiry_rejects_stale_revision_and_not_due_accounts_without_freezing_points() {
        var c=expiryFixture();
        var expiry=context.getBean(ExpiryService.class);
        assertThatThrownBy(() -> expiry.prepare(expiryRequest(c,6,2000)))
                .hasMessage("POINTS_EXPIRY_REVISION_CONFLICT");
        assertThatThrownBy(() -> expiry.prepare(expiryRequest(c,7,999))).hasMessage("POINTS_EXPIRY_NOT_DUE");
        assertThat(jdbc().queryForObject("SELECT available_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER'",Long.class,c.typeCode())).isEqualTo(70);
    }

    @Test void switches_stop_new_earning_but_allow_previously_promised_payout_to_finish() {
        var c=fixture(true);
        addEarnBudget(c);
        var switches=context.getBean(PointsSwitchService.class);
        switches.change(new PointsSwitchService.Command("OFF:"+c.typeCode(),Scope.TYPE,c.typeCode(),c.typeCode(),
                false,100,"OP","MAINTENANCE"));
        assertThat(context.getBean(PointsReadStore.class).enabled(c.typeCode(),"U","STORE")).isFalse();
        var result=context.getBean(EarnService.class).grant(earnCommand(c));
        assertThat(result.status()).isEqualTo(PointsEarnPort.GrantStatus.SKIPPED);
        assertThat(result.reason()).isEqualTo("POINTS_DISABLED");
        var ticket=begin(c); execute(ticket.ticketNo());
        assertThat(execute(ticket.ticketNo()).status()).isEqualTo(FundsStatus.SUCCEEDED);
    }

    @Test void expiry_uses_union_of_type_and_user_pauses_without_extending_twice() {
        var c=expiryFixture();
        var switches=context.getBean(PointsSwitchService.class);
        switches.change(new PointsSwitchService.Command("TYPE_OFF:"+c.typeCode(),Scope.TYPE,c.typeCode(),c.typeCode(),
                false,100,"OP","MAINTENANCE"));
        switches.change(new PointsSwitchService.Command("USER_OFF:"+c.typeCode(),Scope.USER,c.typeCode(),"U",
                false,200,"OP","UNAVAILABLE"));
        switches.change(new PointsSwitchService.Command("TYPE_ON:"+c.typeCode(),Scope.TYPE,c.typeCode(),c.typeCode(),
                true,300,"OP","RECOVERED"));
        switches.change(new PointsSwitchService.Command("USER_ON:"+c.typeCode(),Scope.USER,c.typeCode(),"U",
                true,500,"OP","RECOVERED"));
        var expiry=context.getBean(ExpiryService.class);
        assertThatThrownBy(() -> expiry.prepare(expiryRequest(c,7,1300))).hasMessage("POINTS_EXPIRY_NOT_DUE");
        var command=expiry.prepare(expiryRequest(c,7,1500));
        assertThat(expiry.prepare(expiryRequest(c,7,1500))).isEqualTo(command);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_pause_interval WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(2);
    }

    @Test void switches_separate_earning_from_redemption_and_match_client_and_merchant_context() {
        var c=fixture(true);
        var switches=context.getBean(PointsSwitchService.class);
        var read=context.getBean(PointsReadStore.class);
        var earn=Flow.EARN;
        var redeem=Flow.REDEEM;
        switches.change(new PointsSwitchService.Command("EARN_OFF:"+c.typeCode(),Scope.TYPE,c.typeCode(),
                c.typeCode(),false,100,"OP","EARN_ONLY",earn));
        assertThat(read.enabled(new PointsReadStore.Availability(c.typeCode(),"U","STORE","M","CN",
                "ONLINE","MINI",earn))).isFalse();
        assertThat(read.enabled(new PointsReadStore.Availability(c.typeCode(),"U","STORE","M","CN",
                "ONLINE","MINI",redeem))).isTrue();
        switches.change(new PointsSwitchService.Command("CLIENT_OFF:"+c.typeCode(),Scope.CLIENT,c.typeCode(),
                "MINI",false,200,"OP","CLIENT_ONLY",redeem));
        assertThat(read.enabled(new PointsReadStore.Availability(c.typeCode(),"U","STORE","M","CN",
                "ONLINE","MINI",redeem))).isFalse();
        assertThat(read.enabled(new PointsReadStore.Availability(c.typeCode(),"U","STORE","M","CN",
                "ONLINE","WEB",redeem))).isTrue();
        switches.change(new PointsSwitchService.Command("MERCHANT_OFF:"+c.typeCode(),Scope.MERCHANT,c.typeCode(),
                "M",false,300,"OP","OWNER_ONLY",redeem));
        assertThat(read.enabled(new PointsReadStore.Availability(c.typeCode(),"U","STORE","M","CN",
                "ONLINE","WEB",redeem))).isFalse();
        assertThat(read.enabled(new PointsReadStore.Availability(c.typeCode(),"U","OTHER","OTHER","CN",
                "ONLINE","WEB",redeem))).isTrue();
    }

    @Test void expiry_reminder_retries_the_same_message_after_notification_committed_but_reply_was_lost() {
        var c=expiryFixture();
        jdbc().update("UPDATE pts_account SET expire_at=100000 WHERE type_code=? AND holder_type='USER'",c.typeCode());
        var reminders=context.getBean(ExpiryReminderService.class);
        reminders.prepare(c.typeCode(),"U",500,1);
        String event="REMIND:"+PointsKeys.position("N",c.typeCode(),"U:7");
        var inbox=context.getBean(TestUserInbox.class);
        inbox.lostReplies.add(event);
        var worker=context.getBean(PointsEventWorker.class);
        assertThat(worker.drain(c.typeCode(),1,500)).isZero();
        assertThat(worker.drain(c.typeCode(),1,501)).isZero();
        assertThat(worker.drain(c.typeCode(),1,2500)).isEqualTo(1);
        assertThat(inbox.sends.get(event)).isEqualTo(2);
        assertThat(inbox.delivered.stream().filter(event::equals).count()).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_outbox WHERE event_no=?",String.class,event))
                .isEqualTo("DONE");
    }

    @Test void source_scan_advances_watermark_only_after_the_whole_page_is_durable() {
        var c=fixture(false,false,false);
        var source=context.getBean(TestSourceFacts.class);
        var scan=context.getBean(PointsSourceReconciler.class);
        var paid=signedFact(c,"SOURCE_PAID",FactKind.PAID,1,"U");
        var finalized=signedFact(c,"SOURCE_FINAL",FactKind.FINALIZED,2,"U");
        var invalid=new LifecycleFact(finalized.eventNo(),"WRONG",finalized.subOrderNo(),"U",c.typeCode(),2,
                FactKind.FINALIZED,100,0,finalized.lines(),finalized.occurredAt());
        source.pages.put(c.typeCode(),new SourceFactsPort.Page(2,List.of(
                new SourceFactsPort.Fact(1,paid,null,null,null,null),
                new SourceFactsPort.Fact(2,invalid,null,null,null,null))));
        assertThatThrownBy(() -> scan.scan(c.typeCode(),10)).hasMessage("POINTS_FACT_HASH_MISMATCH");
        var cursor=context.getBean(PointsCursorStore.class);
        assertThat(cursor.current("POINTS_SOURCE:"+c.typeCode())).isZero();
        source.pages.put(c.typeCode(),new SourceFactsPort.Page(2,List.of(
                new SourceFactsPort.Fact(1,paid,null,null,null,null),
                new SourceFactsPort.Fact(2,finalized,null,null,null,null))));
        assertThat(scan.scan(c.typeCode(),10).watermark()).isEqualTo(2);
        assertThat(scan.scan(c.typeCode(),10).queued()).isZero();
        assertThat(context.getBean(PointsEventWorker.class)
                .drain(c.typeCode(),10,0)).isEqualTo(2);
    }

    @Test void account_freeze_is_audited_idempotent_and_extends_the_inactivity_clock() {
        var c=expiryFixture();
        var control=context.getBean(PointsAccountControlService.class);
        var freeze=new PointsAccountControlService.Command(
                "FREEZE:"+c.typeCode(),c.typeCode(),"U",true,100,"OP","RISK_REVIEW");
        control.freeze(freeze); control.freeze(freeze);
        assertThat(jdbc().queryForObject("SELECT frozen FROM pts_account WHERE type_code=? AND holder_type='USER'",
                Integer.class,c.typeCode())).isEqualTo(1);
        assertThatThrownBy(() -> context.getBean(ExpiryService.class).prepare(expiryRequest(c,7,2000)))
                .hasMessage("POINTS_FROZEN");
        control.freeze(new PointsAccountControlService.Command(
                "THAW:"+c.typeCode(),c.typeCode(),"U",false,500,"OP","REVIEW_CLEARED"));
        assertThatThrownBy(() -> context.getBean(ExpiryService.class).prepare(expiryRequest(c,7,1300)))
                .hasMessage("POINTS_EXPIRY_NOT_DUE");
        assertThat(context.getBean(ExpiryService.class).prepare(expiryRequest(c,7,1500)).amountMinor()).isEqualTo(70);
    }

    @Test void expiry_clock_pauses_when_every_accepting_store_is_off_but_not_when_another_store_is_usable() {
        var c=expiryFixture();
        var switches=context.getBean(PointsSwitchService.class);
        switches.change(new PointsSwitchService.Command("ONLY_STORE_OFF:"+c.typeCode(),Scope.STORE,c.typeCode(),
                "STORE",false,100,"OP","NO_ACCEPTANCE"));
        assertThatThrownBy(() -> context.getBean(ExpiryService.class).prepare(expiryRequest(c,7,2000)))
                .hasMessage("POINTS_EXPIRY_NOT_DUE");
        acceptanceHistory(c,"SECOND_STORE");
        assertThat(context.getBean(ExpiryService.class).prepare(expiryRequest(c,7,2000)).amountMinor()).isEqualTo(70);
    }

    private void acceptanceHistory(PayoutCommand c,String store) {
        var agreement=new AgreementSnapshot("AGR:"+store,c.typeCode(),"M",store,"OWNER","V1","HASH","TEXT",
                "PER_ORDER","AGGREGATED","TEST","A1","ACTIVE");
        jdbc().update("INSERT INTO pts_merchant_agreement(type_code,state,payload_json,agreement_no,store_no,"
                + "active_key,created_at,updated_at) VALUES(?,'ACTIVE',?,?,?,?,?,?)",c.typeCode(),
                json.writeValueAsString(agreement),"AGR:"+c.typeCode()+":"+store,store,"AK:"+c.typeCode()+":"+store,
                Timestamp.from(Instant.EPOCH),Timestamp.from(Instant.EPOCH));
    }

    @Test void expiry_reminder_is_durable_once_per_revision_and_old_notice_is_invalid_after_activity() {
        var c=expiryFixture();
        var reminders=context.getBean(ExpiryReminderService.class);
        assertThat(reminders.prepare(c.typeCode(),"U",500,1)).isTrue();
        assertThat(reminders.prepare(c.typeCode(),"U",501,1)).isFalse();
        String event="REMIND:"+PointsKeys.position("N",c.typeCode(),"U:7");
        var message=json.readValue(jdbc().queryForObject("SELECT payload_json FROM pts_outbox WHERE event_no=?",
                String.class,event),PointsEventQueue.Message.class);
        var notice=json.readValue(message.payload(),
                ExpiryReminderService.Reminder.class);
        assertThat(notice.points()).isEqualTo(70);
        assertThat(reminders.current(notice)).isTrue();
        jdbc().update("UPDATE pts_account SET expiry_revision=8 WHERE type_code=? AND holder_type='USER'",c.typeCode());
        assertThat(reminders.current(notice)).isFalse();
    }

    @Test void source_finalization_arriving_before_payment_retries_without_losing_the_fact() {
        var c=fixture(false,false,false);
        var ingress=context.getBean(PointsFactPort.class);
        var worker=context.getBean(PointsEventWorker.class);
        ingress.accept(signedFact(c,"FINAL",FactKind.FINALIZED,2,"U"));
        ingress.accept(signedFact(c,"PAID",FactKind.PAID,1,"U"));
        assertThat(worker.drain(c.typeCode(),10,0)).isEqualTo(1);
        assertThat(worker.drain(c.typeCode(),10,3000)).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_outbox WHERE event_no=?",String.class,
                "SOURCE:FINAL:"+c.typeCode())).isEqualTo("DONE");
    }

    @Test void source_cancel_releases_only_its_own_hold_and_prevents_late_earning_and_payment() {
        var c=fixture(false,false,false);
        var ingress=context.getBean(PointsFactPort.class);
        var worker=context.getBean(PointsEventWorker.class);
        ingress.accept(signedFact(c,"CANCEL",FactKind.CANCELLED,2,"U"));
        assertThat(worker.drain(c.typeCode(),10,0)).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT reserved_b_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isZero();
        assertThat(jdbc().queryForObject("SELECT available_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='USER'",Long.class,c.typeCode())).isEqualTo(100);
        addEarnBudget(c);
        assertThat(context.getBean(EarnService.class).grant(earnCommand(c)).reason()).isEqualTo("POINTS_ORDER_CANCELLED");
        ingress.accept(signedFact(c,"LATE_PAID",FactKind.PAID,1,"U"));
        worker.drain(c.typeCode(),10,0);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_redeem_position WHERE position_no=?",String.class,
                c.positionIds().getFirst())).isEqualTo("RELEASED");
    }

    @Test void source_same_phase_and_version_cannot_rebind_user_after_cancellation() {
        var c=fixture(false,false,false);
        var handler=context.getBean(PointsSourceFactHandler.class);
        handler.apply(signedFact(c,"CANCEL",FactKind.CANCELLED,2,"U"));
        assertThatThrownBy(() -> handler.apply(signedFact(c,"OTHER_CANCEL",FactKind.CANCELLED,2,"OTHER_USER")))
                .hasMessage("POINTS_FACT_VERSION_CONFLICT");
    }

    private LifecycleFact signedFact(PayoutCommand c,String event,FactKind kind,long version,String user) {
        var raw=new LifecycleFact(event+":"+c.typeCode(),null,c.sourceRef(),user,c.typeCode(),version,kind,
                100,0,List.of(new LineSnapshot("I",1,100,0)),Instant.parse("2026-10-15T00:00:00Z"));
        return new LifecycleFact(raw.eventNo(),PointsFactPortImpl.digest(raw),
                raw.subOrderNo(),raw.userNo(),raw.typeCode(),raw.sourceVersion(),raw.kind(),raw.finalGoodsMinor(),
                raw.refundedGoodsMinor(),raw.lines(),raw.occurredAt());
    }

    @Test void source_fact_is_durable_before_delivery_and_replays_after_worker_acknowledgement() {
        var c=fixture(false,false,false);
        var raw=new LifecycleFact("QUEUE_PAID:"+c.typeCode(),null,c.sourceRef(),"U",c.typeCode(),1,
                FactKind.PAID,100,0,List.of(new LineSnapshot("I",1,100,0)),Instant.parse("2026-10-15T00:00:00Z"));
        var fact=new LifecycleFact(raw.eventNo(),
                PointsFactPortImpl.digest(raw),raw.subOrderNo(),raw.userNo(),
                raw.typeCode(),raw.sourceVersion(),raw.kind(),raw.finalGoodsMinor(),0,raw.lines(),raw.occurredAt());
        var ingress=context.getBean(PointsFactPort.class);
        ingress.accept(fact);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_redeem_position WHERE position_no=?",String.class,
                c.positionIds().getFirst())).isEqualTo("HELD");
        var worker=context.getBean(PointsEventWorker.class);
        assertThat(worker.drain(c.typeCode(),1,0)).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT state FROM pts_redeem_position WHERE position_no=?",String.class,
                c.positionIds().getFirst())).isEqualTo("PAYABLE");
        ingress.accept(fact);
        assertThat(worker.drain(c.typeCode(),1,1)).isZero();
        assertThatThrownBy(() -> ingress.accept(raw)).hasMessage("POINTS_FACT_HASH_MISMATCH");
        assertThat(jdbc().queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,
                c.typeCode())).isEqualTo(30);
    }

    @Test void event_queue_rejects_conflicting_payload_and_recovers_expired_leases() {
        var c=fixture(true);
        var queue=context.getBean(PointsEventQueue.class);
        var message=new PointsEventQueue.Message(
                PointsEventQueue.Kind.SOURCE_FACT,"{}");
        String event="SOURCE:"+c.typeCode();
        queue.enqueue(c.typeCode(),event,message);
        queue.enqueue(c.typeCode(),event,message);
        assertThatThrownBy(() -> queue.enqueue(c.typeCode(),event,
                new PointsEventQueue.Message(message.kind(),"{\"changed\":true}")))
                .hasMessage("POINTS_EVENT_CONFLICT");
        var first=queue.claim(c.typeCode(),0);
        assertThat(first.eventNo()).isEqualTo(event);
        assertThat(queue.claim(c.typeCode(),1)).isNull();
        var recovered=queue.claim(c.typeCode(),300000);
        assertThat(recovered.id()).isEqualTo(first.id());
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThatThrownBy(() -> queue.complete(first)).hasMessage("POINTS_EVENT_LEASE_LOST");
        queue.complete(recovered);
        assertThat(queue.claim(c.typeCode(),600000)).isNull();
    }

    @Test void event_queue_records_manual_failure_and_requires_audited_retry() {
        var c=fixture(true);
        var queue=context.getBean(PointsEventQueue.class);
        String event="MANUAL:"+c.typeCode();
        queue.enqueue(c.typeCode(),event,new PointsEventQueue.Message(
                PointsEventQueue.Kind.SOURCE_FACT,"{}"));
        var lease=queue.claim(c.typeCode(),0);
        queue.fail(lease,0,true,"SOURCE_BODY_CONFLICT");
        assertThat(queue.claim(c.typeCode(),3600000)).isNull();
        assertThatThrownBy(() -> queue.retry(lease.id(),"","",3600000))
                .hasMessage("POINTS_EVENT_AUDIT_REQUIRED");
        queue.retry(lease.id(),"OP","source repaired",3600000);
        var resumed=queue.claim(c.typeCode(),3600000);
        assertThat(resumed.eventNo()).isEqualTo(event);
        queue.complete(resumed);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_inbox WHERE type_code=? AND state='AUDIT'",
                Long.class,c.typeCode())).isEqualTo(2);
    }

    @Test void retirement_requires_zero_backing_and_no_pending_approval() {
        var c=fixture(true);
        jdbc().update("UPDATE pts_point_type SET status='DRAINING' WHERE type_code=?",c.typeCode());
        var retirement=context.getBean(TypeRetirementService.class);
        assertThatThrownBy(() -> retirement.retire(c.typeCode(),"OP"))
                .hasMessage("POINTS_RETIREMENT_OBLIGATIONS_REMAIN");
        String empty="RT"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        jdbc().update("INSERT INTO pts_point_type(type_code,market,currency,rate_points,rate_minor,status)"
                + " VALUES(?,'CN','CNY',1,1,'DRAINING')",empty);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')",empty);
        jdbc().update("INSERT INTO pts_pool_request(type_code,state,payload_json,request_no,submitted_by)"
                + " VALUES(?,'SUBMITTED','{}',?,'OP')",empty,"PENDING:"+empty);
        assertThatThrownBy(() -> retirement.retire(empty,"OP"))
                .hasMessage("POINTS_RETIREMENT_OBLIGATIONS_REMAIN");
        jdbc().update("UPDATE pts_pool_request SET state='REJECTED' WHERE type_code=?",empty);
        assertThat(retirement.retire(empty,"OP").status()).isEqualTo(PointType.Status.RETIRED);
        assertThat(retirement.retire(empty,"OP").status()).isEqualTo(PointType.Status.RETIRED);
        var lateRequest=new PoolRequest("LATE:"+empty,empty,PoolRequest.Action.TOPUP_B,1,"MAKER","UNVERIFIED");
        assertThatThrownBy(() -> context.getBean(PointsConfigurationStore.class).submit(lateRequest,
                json.writeValueAsString(new Envelope(lateRequest,null)))).hasMessage("POINTS_TYPE_RETIRED");
    }

    @Test void switches_store_scope_blocks_only_that_store_and_cannot_rebind_an_earning_snapshot() {
        var c=fixture(true);
        addEarnBudget(c);
        context.getBean(PointsSwitchService.class).change(new PointsSwitchService.Command("STORE_OFF:"+c.typeCode(),
                Scope.STORE,c.typeCode(),"STORE",false,100,"OP","STORE_PAUSE"));
        var earning=context.getBean(EarnService.class);
        var original=earnCommand(c);
        assertThat(earning.grant(original).status()).isEqualTo(PointsEarnPort.GrantStatus.SKIPPED);
        var changed=new PointsEarnPort.GrantCommand(original.eventNo(),original.subOrderNo(),original.userNo(),
                original.typeCode(),"OTHER_STORE",original.payChannel(),original.payScene(),original.sourceVersion(),
                original.lines());
        assertThatThrownBy(() -> earning.grant(changed)).hasMessage("POINTS_IDEMPOTENCY_CONFLICT");
        var other=new PointsEarnPort.GrantCommand("OTHER:"+c.typeCode(),"OTHER_SUB:"+c.typeCode(),"U",c.typeCode(),
                "OTHER_STORE","TEST","MP",1,original.lines());
        assertThat(earning.grant(other).status()).isEqualTo(PointsEarnPort.GrantStatus.GRANTED);
    }

    private PayoutCommand expiryFixture() {
        var c=fixture(false,false,false);
        acceptanceHistory(c,"STORE");
        jdbc().update("UPDATE pts_account SET expire_at=1000,expiry_revision=7,last_active_at=0"
                + " WHERE type_code=? AND holder_type='USER'",c.typeCode());
        return c;
    }
    private ExpiryService.Request expiryRequest(PayoutCommand c,long revision,long asOf) {
        return new ExpiryService.Request(c.typeCode(),"U",revision,asOf,new FundsContext(FundsPath.AGGREGATED,
                "TEST","PLATFORM","PLATFORM","INCOME_ACCOUNT_V1",null,null,null,0));
    }

    private void completeZeroRefund(PayoutCommand c,String suffix,long refundedGoods) {
        String afterSale=suffix+":"+c.typeCode();
        var command=new RefundCommand(afterSale,c.sourceRef(),"U",c.typeCode(),"CNY",1,0,refundedGoods,
                List.of(new RefundLine("I",refundedGoods)),new PaymentSnapshot("AGGREGATED","TEST","M","STORE",
                "A1","SM","TRADE",c.sourceRef(),100));
        var preparation=context.getBean(PointsRefundPreparationService.class).prepare(command);
        var proof=new ZeroCashCompletionFact("ZERO:"+afterSale,preparation.preparationNo(),preparation.inputHash(),1,
                c.typeCode(),afterSale,c.sourceRef(),"U","STORE","CNY",1,0,0,0,ZeroCashReason.NO_CASH_DUE,
                Instant.parse("2026-10-16T00:00:00Z"));
        context.getBean(PointsRefundZeroCashService.class).accept(proof);
        context.getBean(PointsRefundConfirmationService.class).confirm(new RefundConfirmation(
                preparation.preparationNo(),preparation.inputHash(),null,null,null,proof.eventNo()));
    }

    private void seedDebt(PayoutCommand c,long amount) {
        // A separate historical store receivable with an explicit synthetic outgoing cash receipt.
        String evidence="DEBT_CASH:"+c.typeCode();
        posting.recordEvidence(new EvidenceRef(evidence,"CNY",-amount));
        var command=new RefundCommand("DEBT:"+c.typeCode(),c.sourceRef(),"U",c.typeCode(),"CNY",1,amount,amount,
                List.of(new RefundLine("I",amount)),new PaymentSnapshot("AGGREGATED","TEST","M","STORE",
                "A1","SM","TRADE",c.sourceRef(),100));
        posting.post(new Voucher("DEBT:"+c.typeCode(),c.typeCode(),"CNY",new RedeemRate(1,1),List.of(),
                List.of(new FundMove(FundBucket.B,-amount)),List.of(),0,
                List.of(new EvidenceRef(evidence,"CNY",-amount)),"{}",List.of(),List.of(),
                List.of(new ReceivableMove("DEBT:"+c.typeCode(),"STORE",-1,"NEW","OPEN",amount,0,0,
                        json.writeValueAsString(command)))));
    }

    private PayoutCommand periodicStatementFixture() { return periodicStatementFixture(30); }
    private PayoutCommand periodicStatementFixture(long points) {
        var c=fixture(false,false,true,points);
        paid(paidFact(c));
        context.getBean(PointsSettlementHook.class).onBillFinalized(finalFact(c,"FINAL:"+c.typeCode(),2));
        return c;
    }

    private PayoutCommand fixture(boolean finalSnapshot) { return fixture(finalSnapshot,true,false); }
    private PayoutCommand fixture(boolean finalSnapshot,boolean paid,boolean periodic) {
        return fixture(finalSnapshot,paid,periodic,30);
    }
    private PayoutCommand fixture(boolean finalSnapshot,boolean paid,boolean periodic,long points) {
        long buffer=points==100?200:100;
        String type="PY"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        jdbc().update("INSERT INTO pts_point_type(type_code,market,currency,rate_points,rate_minor,status)"
                + " VALUES(?,'CN','CNY',1,1,'ACTIVE')",type);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')",type);
        jdbc().update("INSERT INTO pts_fund_evidence(evidence_no,currency,external_minor,verified)"
                + " VALUES(?,'CNY',?,1)","E:"+type,100+buffer);
        posting.post(new Voucher("F:"+type,type,"CNY",new RedeemRate(1,1),List.of(
                move(type,HolderType.MINT,"PLATFORM",Bucket.ISSUED,-100),
                move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,100)),List.of(
                new FundMove(FundBucket.U,100),new FundMove(FundBucket.B,buffer)),List.of(),0,
                List.of(new EvidenceRef("E:"+type,"CNY",100+buffer)),"{}"));
        posting.post(new Voucher("G:"+type,type,"CNY",new RedeemRate(1,1),List.of(
                move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,-100),
                move(type,HolderType.USER,"U",Bucket.AVAILABLE,100-points),
                move(type,HolderType.USER,"U",Bucket.HELD,points)),List.of(
                new FundMove(FundBucket.U,-100),new FundMove(FundBucket.C,100)),List.of(),0,List.of(),"{}"));
        String sub="S:"+type;
        String position=PointsKeys.position("R",type,sub);
        FundsContext funds=new FundsContext(FundsPath.AGGREGATED,"TEST","M","STORE","A1","SM","TRADE",sub,100);
        var agreement=new AgreementSnapshot("A:"+type,type,"M","STORE","OWNER","v1","HASH","TEST TERMS",
                periodic?"PERIODIC":"PER_ORDER","AGGREGATED","TEST","A1","ACTIVE");
        var snapshot=new RedeemSnapshot(sub,"U","O:"+type,new Share(sub,"M","STORE",points,points),
                new Target(sub,"M","STORE",100,"AGGREGATED","TEST","A1","SM","TRADE",
                        List.of(new Item("ITEM",100))),agreement,funds,finalSnapshot);
        posting.post(new Voucher("P:"+type,type,"CNY",new RedeemRate(1,1),paid?List.of(
                move(type,HolderType.USER,"U",Bucket.HELD,-points),
                move(type,HolderType.RECEIVE,"PLATFORM",Bucket.REFUND_RESERVED,points)):List.of(),paid?List.of(
                new FundMove(FundBucket.C,-points),new FundMove(FundBucket.D,points)):List.of(),List.of(
                new PositionMove(PositionMove.Kind.REDEEM,position,-1,"NEW",paid?"PAYABLE":"HELD",points,0,
                        json.writeValueAsString(snapshot))),points,List.of(),"{}",
                List.of(new ReserveMove(position,position,-1,points,points,0,0))));
        return new PayoutCommand("PAY:"+type,type,sub,List.of(position),FundsPurpose.INTERNAL_REPLENISHMENT,funds,"CNY");
    }
    private PayoutTicket begin(PayoutCommand command) {
        try {
            Class<?> type=Class.forName("ai.neargo.shop.pay.points.payout.PointsPayoutService");
            return (PayoutTicket)type.getMethod("beginPayout",PayoutCommand.class)
                    .invoke(context.getBean(type),command);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) { throw new AssertionError("Payout preparation service missing",e); }
    }
    private PointsMove move(String type,HolderType holder,String no,Bucket bucket,long points) {
        return new PointsMove(new AccountKey(type,holder,no,bucket),points);
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
    @Configuration
    @Import({PointsPostingDatabaseTest.Config.class,MybatisPointsReadStore.class,
            MybatisPointsConfigurationStore.class,MybatisPointsFactStore.class,EarnServiceImpl.class,
            MybatisStatementStore.class,MybatisPointsCashRefundReadStore.class,MybatisLifecycleStore.class,
            MybatisPointsCursorStore.class,
            PointsSwitchServiceImpl.class})
    @ComponentScan(basePackages={"ai.neargo.shop.pay.points.payout","ai.neargo.shop.pay.points.app.impl",
            "ai.neargo.shop.pay.points.statement","ai.neargo.shop.pay.points.refund.impl",
            "ai.neargo.shop.pay.points.lifecycle.impl","ai.neargo.shop.pay.points.event.impl","ai.neargo.shop.pay.points.recon.impl"})
    static class Config {
        @Bean TestUserInbox userInbox() { return new TestUserInbox(); }
        @Bean TestSourceFacts sourceFacts() { return new TestSourceFacts(); }
        @Bean LostReplyFunds testFunds() { return new LostReplyFunds(); }
    }
    static class TestUserInbox implements UserInboxPort {
        final Set<String> delivered=ConcurrentHashMap.newKeySet();
        final Set<String> lostReplies=ConcurrentHashMap.newKeySet();
        final Map<String,Integer> sends=new ConcurrentHashMap<>();
        @Override public boolean deliverMarketing(String user,String title,String body,String link,String key) {
            throw new AssertionError("Financial reminders must not use marketing delivery");
        }
        @Override public void pointsExpiring(PointsExpiry notice) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends.merge(notice.dedupKey(),1,Integer::sum);
            delivered.add(notice.dedupKey());
            if (lostReplies.remove(notice.dedupKey())) { throw new IllegalStateException("SIMULATED_REPLY_LOST"); }
        }
    }
    static class TestSourceFacts implements SourceFactsPort {
        final Map<String,Page> pages=new ConcurrentHashMap<>();
        @Override public Page scan(String type,long after,int limit) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var page=pages.get(type);
            return page==null || page.nextSequence()<=after?new Page(after,List.of()):page;
        }
    }
    /** Simulates a bank that completed the operation but whose first reply was lost; never a production adapter. */
    static class LostReplyFunds implements PointsFundsGateway {
        final Map<String,Integer> sends=new ConcurrentHashMap<>();
        final Map<String,Integer> queries=new ConcurrentHashMap<>();
        final Map<String,FundsCommand> originals=new ConcurrentHashMap<>();
        final Set<String> corruptReceipts=ConcurrentHashMap.newKeySet();
        final Set<String> rejections=ConcurrentHashMap.newKeySet();
        @Override public FundsCapabilities capabilities(FundsContext c) {
            return new FundsCapabilities(true,true,true,false,false,true);
        }
        @Override public FundsResult submit(FundsCommand c) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends.merge(c.ticketNo(),1,Integer::sum);
            if (rejections.contains(c.ticketNo())) {
                return new FundsResult(c.ticketNo(),FundsStatus.FAILED,null,c.amountMinor(),"TEST_REJECTED");
            }
            originals.put(c.ticketNo(),c);
            return new FundsResult(c.ticketNo(),FundsStatus.UNKNOWN,null,c.amountMinor(),"TEST_REPLY_LOST");
        }
        @Override public FundsResult query(FundsCommand c) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(c).isEqualTo(originals.get(c.ticketNo()));
            queries.merge(c.ticketNo(),1,Integer::sum);
            return new FundsResult(corruptReceipts.contains(c.ticketNo())?"FOREIGN_TICKET":c.ticketNo(),
                    FundsStatus.SUCCEEDED,"TEST-PAID:"+c.ticketNo(),c.amountMinor(),null);
        }
    }
}
