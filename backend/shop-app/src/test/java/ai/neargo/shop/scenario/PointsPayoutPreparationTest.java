package ai.neargo.shop.scenario;

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

/** Isolated fixture facts use real pay posting, mapper, and transaction factory without booting the application. */
@SpringJUnitConfig(PointsPayoutPreparationTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties={
        "spring.datasource.url=jdbc:h2:mem:points_payout_prepare;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "shop.points.enabled=true", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
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
        jdbc().execute("CREATE TABLE stl_bill (id BIGINT AUTO_INCREMENT PRIMARY KEY,settle_no VARCHAR(64),"
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
        return new PointsEarnPort.GrantCommand("EARN:"+c.typeCode(),c.sourceRef(),"U",c.typeCode(),"TEST","MP",1,
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

    private PayoutCommand fixture(boolean finalSnapshot) { return fixture(finalSnapshot,true,false); }
    private PayoutCommand fixture(boolean finalSnapshot,boolean paid,boolean periodic) {
        String type="PY"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        jdbc().update("INSERT INTO pts_point_type(type_code,market,currency,rate_points,rate_minor,status)"
                + " VALUES(?,'CN','CNY',1,1,'ACTIVE')",type);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')",type);
        jdbc().update("INSERT INTO pts_fund_evidence(evidence_no,currency,external_minor,verified)"
                + " VALUES(?,'CNY',200,1)","E:"+type);
        posting.post(new Voucher("F:"+type,type,"CNY",new RedeemRate(1,1),List.of(
                move(type,HolderType.MINT,"PLATFORM",Bucket.ISSUED,-100),
                move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,100)),List.of(
                new FundMove(FundBucket.U,100),new FundMove(FundBucket.B,100)),List.of(),0,
                List.of(new EvidenceRef("E:"+type,"CNY",200)),"{}"));
        posting.post(new Voucher("G:"+type,type,"CNY",new RedeemRate(1,1),List.of(
                move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,-100),
                move(type,HolderType.USER,"U",Bucket.AVAILABLE,70),
                move(type,HolderType.USER,"U",Bucket.HELD,30)),List.of(
                new FundMove(FundBucket.U,-100),new FundMove(FundBucket.C,100)),List.of(),0,List.of(),"{}"));
        String sub="S:"+type;
        String position=PointsKeys.position("R",type,sub);
        FundsContext funds=new FundsContext(FundsPath.AGGREGATED,"TEST","M","STORE","A1","SM","TRADE",sub,100);
        var agreement=new AgreementSnapshot("A:"+type,type,"M","STORE","OWNER","v1","HASH","TEST TERMS",
                periodic?"PERIODIC":"PER_ORDER","AGGREGATED","TEST","A1","ACTIVE");
        var snapshot=new RedeemSnapshot(sub,"U","O:"+type,new Share(sub,"M","STORE",30,30),
                new Target(sub,"M","STORE",100,"AGGREGATED","TEST","A1","SM","TRADE",
                        List.of(new Item("ITEM",100))),agreement,funds,finalSnapshot);
        posting.post(new Voucher("P:"+type,type,"CNY",new RedeemRate(1,1),paid?List.of(
                move(type,HolderType.USER,"U",Bucket.HELD,-30),
                move(type,HolderType.RECEIVE,"PLATFORM",Bucket.REFUND_RESERVED,30)):List.of(),paid?List.of(
                new FundMove(FundBucket.C,-30),new FundMove(FundBucket.D,30)):List.of(),List.of(
                new PositionMove(PositionMove.Kind.REDEEM,position,-1,"NEW",paid?"PAYABLE":"HELD",30,0,
                        json.writeValueAsString(snapshot))),30,List.of(),"{}",
                List.of(new ReserveMove(position,position,-1,30,30,0,0))));
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
            MybatisPointsConfigurationStore.class,MybatisPointsFactStore.class,EarnServiceImpl.class})
    @ComponentScan(basePackages={"ai.neargo.shop.pay.points.payout","ai.neargo.shop.pay.points.app.impl"})
    static class Config {
        @Bean LostReplyFunds testFunds() { return new LostReplyFunds(); }
    }
    /** Simulates a bank that completed the operation but whose first reply was lost; never a production adapter. */
    static class LostReplyFunds implements PointsFundsGateway {
        final Map<String,Integer> sends=new ConcurrentHashMap<>();
        final Map<String,Integer> queries=new ConcurrentHashMap<>();
        final Map<String,FundsCommand> originals=new ConcurrentHashMap<>();
        final Set<String> corruptReceipts=ConcurrentHashMap.newKeySet();
        final Set<String> rejections=ConcurrentHashMap.newKeySet();
        @Override public FundsCapabilities capabilities(FundsContext c) {
            return new FundsCapabilities(true,true,true,false);
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
