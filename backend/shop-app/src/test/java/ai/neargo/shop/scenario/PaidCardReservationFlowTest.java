package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementGrantService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssuePolicyService;
import ai.neargo.shop.pay.entitlement.service.EntitlementSaleReservationService;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReleaseSale;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReserveSale;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.State;
import ai.neargo.shop.trade.entitlement.CardSaleSnapshotPlan;
import ai.neargo.shop.trade.entitlement.CardSaleSnapshotService;
import ai.neargo.shop.trade.port.CardSaleGrantSourcePortImpl;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"shop.pay.entitlement.catalog.enabled=true", "shop.pay.entitlement.issue.enabled=true",
        "shop.pay.entitlement.use.enabled=true", "shop.pay.paid-card.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:sale-reservation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class PaidCardReservationFlowTest {
    @Autowired EntitlementSaleReservationService sales;
    @Autowired ai.neargo.shop.pay.entitlement.service.EntitlementReconciliationService reconciliation;
    @Autowired ai.neargo.shop.pay.entitlement.service.EntitlementExceptionService exceptions;
    @Autowired ai.neargo.shop.pay.entitlement.service.EntitlementExceptionRecoveryService exceptionRecovery;
    @Autowired ai.neargo.shop.pay.entitlement.service.EntitlementExceptionActionWriter exceptionActions;
    @Autowired ai.neargo.shop.paybridge.CardSaleRecoveryService recovery;
    @Autowired ai.neargo.shop.spi.entitlement.PaidCardSaleRecoveryPort recoverySource;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    ai.neargo.shop.spi.entitlement.PaidCardPaymentGatePort paymentGate;
    @Autowired ai.neargo.shop.trade.service.MerchantOrderService merchantOrders;
    @Autowired ai.neargo.shop.paybridge.CardSaleCoordinator coordinator;
    @Autowired ai.neargo.shop.paybridge.CardSaleEventConsumer paidConsumer;
    @Autowired EntitlementCatalogService catalog;
    @Autowired EntitlementIssuePolicyService policies;
    @Autowired EntitlementGrantService grants;
    @Autowired CardSaleSnapshotService snapshots;
    @Autowired CardSaleGrantSourcePortImpl sources;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("transactionManager") PlatformTransactionManager transactions;
    private CatalogScope scope;
    private EntitlementContext ctx;
    private ReserveSale command;
    private String template;
    private String order;
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }

    @BeforeEach void pendingPricedCardOrder() {
        scope = new CatalogScope("MAIN", id(), "OP1", false);
        ctx = new EntitlementContext("MAIN", "OP1", id(), "a".repeat(64)); order = id();
        var definition = new EntitlementDefinition("30 services", "PAID_CARD", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "COUNT", 30, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "FIRST_USE", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of("PAID_ORDER"));
        var head = catalog.create(scope, definition, id()); template = head.templateNo();
        var version = catalog.publish(scope, template, head.revision(), id());
        policies.save(scope, template, new SavePolicy(0, 1L, 0L, 1, true), id());
        var sale = snapshots.capture(new CardSaleSnapshotPlan.OrderFacts(order, "U1", "CNY"), List.of(
                new CardSaleSnapshotPlan.SaleLine("OL" + order, "SUB1", scope.entityNo(), "S1", "G1", "SKU1",
                        1000, 1, List.of(new CardSaleSnapshotPlan.EntitlementRule(version.versionNo(), "COUNT",
                                json.writeValueAsString(definition)))))).getFirst();
        jdbc.update("INSERT INTO ord_order(order_no,user_no,pay_amount,currency,status,pay_deadline_at,tenant_no,"
                + "created_at,updated_at) VALUES (?,'U1',1000,'CNY','WAIT_PAY',?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                order, System.currentTimeMillis() + 900000);
        command = new ReserveSale(ReserveSale.number(sale.source()), sale.source(), version.versionNo(), "U1",
                scope.entityNo(), sale.snapshotHash());
    }

    @Test void prepaymentHoldTransfersWithoutReservingTwiceAndSurvivesTemplatePause() {
        assertThat(sales.reserve(ctx, command).status()).isEqualTo(State.RESERVED);
        assertThat(sales.reserve(ctx, command).status()).isEqualTo(State.RESERVED);
        assertThat(policies.get(scope, template).heldCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?", Integer.class,
                scope.entityNo())).isZero();
        var head = catalog.get(scope, template);
        catalog.changeStatus(scope, template, head.revision(), "PAUSED", id());
        var at = Instant.ofEpochMilli(System.currentTimeMillis());
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=?,pay_channel='WECHAT',pay_trade_no='PAY1' "
                + "WHERE order_no=?", at.toEpochMilli(), order);
        snapshots.recordPaidFromOrder(order);
        var grant = new EntitlementGrantPort.GrantCommand("G" + id(), command.sourceRef(), null,
                command.templateVersionNo(), "U1", scope.entityNo(), 1, command.benefitSnapshotHash(), "PAY1");
        assertThat(grants.prepare(ctx, grant).status()).isEqualTo(EntitlementGrantPort.State.PREPARED);
        assertThat(policies.get(scope, template).heldCount()).isEqualTo(1);
        assertThat(sales.query(ctx, scope.entityNo(), command.reservationNo()).status()).isEqualTo(State.TRANSFERRED);
        var decision = sources.decision(ctx, command.sourceRef()).decision();
        var commit = new EntitlementGrantPort.GrantDecision(grant.grantNo(), command.sourceRef(), decision);
        var issued = grants.commit(ctx, commit);
        assertThat(grants.commit(ctx, commit)).isEqualTo(issued);
        assertThat(issued.instanceNos()).hasSize(1);
        assertThat(policies.get(scope, template).heldCount()).isZero();
        assertThat(policies.get(scope, template).issuedCount()).isEqualTo(1);
    }

    @Test void onlyDurableCancellationReleasesAndAnEarlierReleaseFencesLateReserve() {
        sales.reserve(ctx, command);
        var fake = new DecisionRef("FAKE", Instant.now(), 0, DecisionRef.DecisionType.GRANT_ABORT);
        assertThat(sales.release(ctx, new ReleaseSale(command, fake)).status()).isEqualTo(State.PROCESSING);
        assertThat(policies.get(scope, template).heldCount()).isEqualTo(1);
        cancel();
        assertThatThrownBy(() -> sales.release(ctx, new ReleaseSale(command, fake))).isInstanceOf(BizException.class);
        var release = new ReleaseSale(command, sources.decision(ctx, command.sourceRef()).decision());
        assertThat(sales.release(ctx, release).status()).isEqualTo(State.RELEASED);
        assertThat(sales.release(ctx, release).status()).isEqualTo(State.RELEASED);
        assertThat(sales.reserve(ctx, command).status()).isEqualTo(State.RELEASED);
        assertThat(policies.get(scope, template).heldCount()).isZero();
        assertThat(policies.get(scope, template).issuedCount()).isZero();
    }

    @Test void cancellationBeforeAnyReservePersistsANegativeClaimWithoutTouchingCapacity() {
        cancel();
        var release = new ReleaseSale(command, sources.decision(ctx, command.sourceRef()).decision());
        assertThat(sales.release(ctx, release).status()).isEqualTo(State.RELEASED);
        assertThat(sales.reserve(ctx, command).status()).isEqualTo(State.RELEASED);
        assertThat(policies.get(scope, template).heldCount()).isZero();
    }

    @Test void paidOrderWithoutPrepaymentPromiseCannotAllocateCapacityAfterCollection() {
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=?,pay_channel='WECHAT',pay_trade_no='PAY1' "
                + "WHERE order_no=?", System.currentTimeMillis(), order);
        snapshots.recordPaidFromOrder(order);
        var grant = new EntitlementGrantPort.GrantCommand("G" + id(), command.sourceRef(), null,
                command.templateVersionNo(), "U1", scope.entityNo(), 1, command.benefitSnapshotHash(), "PAY1");
        assertThatThrownBy(() -> grants.prepare(ctx, grant)).isInstanceOf(BizException.class);
        assertThat(policies.get(scope, template).heldCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE grant_no=?", Integer.class,
                grant.grantNo())).isZero();
    }

    @Test void capacityFailureRollsBackReservationAndSameNumberCanRetryAfterCancellation() {
        sales.reserve(ctx, command);
        var other = id();
        var d = catalog.getVersion(scope, command.templateVersionNo()).definition();
        var snapshot = snapshots.capture(new CardSaleSnapshotPlan.OrderFacts(other, "U1", "CNY"), List.of(
                new CardSaleSnapshotPlan.SaleLine("OL" + other, "SUB2", scope.entityNo(), "S1", "G1", "SKU1",
                        1000, 1, List.of(new CardSaleSnapshotPlan.EntitlementRule(command.templateVersionNo(), "COUNT",
                                json.writeValueAsString(d)))))).getFirst();
        jdbc.update("INSERT INTO ord_order(order_no,user_no,pay_amount,currency,status,pay_deadline_at,tenant_no,"
                + "created_at,updated_at) VALUES (?,'U1',1000,'CNY','WAIT_PAY',?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                other, System.currentTimeMillis() + 900000);
        var second = new ReserveSale(ReserveSale.number(snapshot.source()), snapshot.source(), command.templateVersionNo(),
                "U1", scope.entityNo(), snapshot.snapshotHash());
        assertThatThrownBy(() -> sales.reserve(ctx, second)).isInstanceOf(BizException.class);
        assertThat(sales.query(ctx, scope.entityNo(), second.reservationNo()).status()).isEqualTo(State.NOT_FOUND);
        assertThat(policies.get(scope, template).heldCount()).isEqualTo(1);
        cancel();
        sales.release(ctx, new ReleaseSale(command, sources.decision(ctx, command.sourceRef()).decision()));
        assertThat(sales.reserve(ctx, second).status()).isEqualTo(State.RESERVED);
        assertThat(policies.get(scope, template).heldCount()).isEqualTo(1);
    }
    @Test void coordinatorResumesOriginalUnitsAcrossPaymentAndRepeatedDelivery() {
        paymentGate.requireReady("MAIN",order);
        var reserved=coordinator.recover(ctx,order);
        assertThat(reserved.readyForPayment()).isTrue();
        assertThat(reserved.reserved()).isEqualTo(1);
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=?,pay_channel='WECHAT',pay_trade_no='PAY1' "
                + "WHERE order_no=?",System.currentTimeMillis(),order);
        snapshots.recordPaidFromOrder(order);
        var issued=coordinator.recover(ctx,order);
        assertThat(issued.issued()).isEqualTo(1);
        assertThat(issued.readyForPayment()).isFalse();
        assertThat(coordinator.recover(ctx,order)).isEqualTo(issued);
        var event=new ai.neargo.shop.event.SysOutbox();
        event.setEventType("ORDER_PAID"); event.setAggregateId(order);
        event.setPayload(json.writeValueAsString(new ai.neargo.shop.spi.trade.OrderEvents.OrderPaid(
                order,"U1",1000,"WECHAT")));
        paidConsumer.consume(event);
        paidConsumer.consume(event);
        assertThat(policies.get(scope,template).issuedCount()).isEqualTo(1);
    }

    @Test void coordinatorReleasesOriginalUnitsAfterDurableCancellation() {
        coordinator.recover(ctx,order);
        cancel();
        assertThat(coordinator.recover(ctx,order).released()).isEqualTo(1);
        var event=new ai.neargo.shop.event.SysOutbox();
        event.setEventType("CARD_SALE_CANCELLED"); event.setAggregateId(order);
        event.setPayload(json.writeValueAsString(new ai.neargo.shop.spi.entitlement.CardSaleEvents.Cancelled(order)));
        paidConsumer.consume(event);
        paidConsumer.consume(event);
        assertThatThrownBy(() -> paymentGate.requireReady("MAIN",order)).isInstanceOf(BizException.class);
        assertThat(policies.get(scope,template).heldCount()).isZero();
    }
    @Test void twoItemsWithTwoRightsIssueExactlyFourInstances() {
        var policy=policies.get(scope,template);
        policies.save(scope,template,new SavePolicy(policy.revision(),10L,0L,10,true),id());
        var second=id();
        var definition=catalog.getVersion(scope,command.templateVersionNo()).definition();
        var rule=new CardSaleSnapshotPlan.EntitlementRule(command.templateVersionNo(),"COUNT",
                json.writeValueAsString(definition));
        snapshots.capture(new CardSaleSnapshotPlan.OrderFacts(second,"U1","CNY"),List.of(
                new CardSaleSnapshotPlan.SaleLine("OL"+second,"SUB2",scope.entityNo(),"S1","G1","SKU1",
                        1000,2,List.of(rule,rule))));
        jdbc.update("INSERT INTO ord_order(order_no,user_no,pay_amount,currency,status,pay_deadline_at,tenant_no,"
                + "created_at,updated_at) VALUES (?,'U1',2000,'CNY','WAIT_PAY',?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                second,System.currentTimeMillis()+900000);
        assertThat(coordinator.recover(ctx,second).reserved()).isEqualTo(4);
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=?,pay_channel='WECHAT',pay_trade_no='PAY2' "
                + "WHERE order_no=?",System.currentTimeMillis(),second);
        snapshots.recordPaidFromOrder(second);
        assertThat(coordinator.recover(ctx,second).issued()).isEqualTo(4);
        assertThat(coordinator.recover(ctx,second).issued()).isEqualTo(4);
        assertThat(policies.get(scope,template).issuedCount()).isEqualTo(4);
        assertThat(policies.get(scope,template).heldCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?",Integer.class,
                scope.entityNo())).isEqualTo(4);
        var ops=new CatalogScope("MAIN",scope.entityNo(),"OP1",true);
        var first=reconciliation.checkRights(ops,new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.CheckRights(
                java.time.LocalDate.of(2026,10,19),"UTC","",2),id());
        assertThat(first.checkedCount()).isEqualTo(2); assertThat(first.nextCursor()).isNotNull();
        var secondPage=reconciliation.checkRights(ops,new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.CheckRights(
                java.time.LocalDate.of(2026,10,19),"UTC",first.nextCursor(),2),id());
        assertThat(secondPage.checkedCount()).isEqualTo(2); assertThat(secondPage.nextCursor()).isNull();
        assertThat(first.diffCount()+secondPage.diffCount()).isZero();
    }

    @Test void offlinePrepaymentRunsOutsideTransactionAndCancellationWinsBeforeCollection() {
        String sub="SUB"+order;
        jdbc.update("UPDATE ord_order SET status='WAIT_OFFLINE_PAY' WHERE order_no=?",order);
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,'U1',?,'S1','WAIT_PAY','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sub,order,scope.entityNo());
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            invocation.callRealMethod();
            jdbc.update("UPDATE ord_order SET status='CANCELLED' WHERE order_no=?",order);
            return null;
        }).when(paymentGate).requireReady("MAIN",order);
        assertThatThrownBy(() -> merchantOrders.confirmOfflinePay(scope.entityNo(),"S1",sub,"OP1"))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?",String.class,order))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT offline_confirmed_by FROM ord_order WHERE order_no=?",
                String.class,order)).isNull();
        assertThat(policies.get(scope,template).issuedCount()).isZero();
    }

    @Test void sweepRecoversLostPaidEventAndPersistsAcknowledgement() {
        assertThat(recoverySource.pending(ctx,"",200)).doesNotContain(order);
        coordinator.recover(ctx,order);
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=?,pay_channel='WECHAT',pay_trade_no='PAY1' "
                + "WHERE order_no=?",System.currentTimeMillis(),order);
        snapshots.recordPaidFromOrder(order);
        assertThat(recoverySource.pending(ctx,"",200)).contains(order);
        recovery.recover(ctx,"",200);
        assertThat(recoverySource.pending(ctx,"",200)).doesNotContain(order);
        assertThat(jdbc.queryForObject("SELECT delivery_status FROM ord_card_sale_snapshot WHERE order_no=?",
                String.class,order)).isEqualTo("ACKNOWLEDGED");
        recovery.recover(ctx,"",200);
        assertThat(policies.get(scope,template).issuedCount()).isEqualTo(1);
    }

    @Test void failedRecoveryRetainsPendingWithoutIssuing() {
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=?,pay_channel='WECHAT',pay_trade_no='PAY1' "
                + "WHERE order_no=?",System.currentTimeMillis(),order);
        snapshots.recordPaidFromOrder(order);
        recovery.recover(ctx,"",200);
        assertThat(recoverySource.pending(ctx,"",200)).contains(order);
        assertThat(policies.get(scope,template).issuedCount()).isZero();
    }

    @Test void capturedGrantExceptionIsDurablePrivateAndPlatformScoped() {
        coordinator.recover(ctx,order);
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=?,pay_channel='WECHAT',pay_trade_no='PRIVATE_RECEIPT' "
                + "WHERE order_no=?",System.currentTimeMillis(),order);
        snapshots.recordPaidFromOrder(order);
        var grant=new EntitlementGrantPort.GrantCommand("G"+id(),command.sourceRef(),null,
                command.templateVersionNo(),"U1",scope.entityNo(),1,command.benefitSnapshotHash(),"PRIVATE_RECEIPT");
        grants.prepare(ctx,grant);
        var issue=exceptions.captureGrantFailure(ctx,scope.entityNo(),grant.grantNo());
        assertThat(exceptions.captureGrantFailure(ctx,scope.entityNo(),grant.grantNo())).isEqualTo(issue);
        assertThat(json.writeValueAsString(issue)).doesNotContain("PRIVATE_RECEIPT","paidFactRef","commandSnapshot");
        var ops=new CatalogScope("MAIN",scope.entityNo(),"OP1",true);
        assertThat(exceptions.get(ops,issue.exceptionNo())).isEqualTo(issue);
        assertThat(exceptions.list(ops,null,"OPEN",1,10).records()).containsExactly(issue);
        assertThatThrownBy(() -> exceptions.get(scope,issue.exceptionNo())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> exceptions.get(new CatalogScope("MAIN","OTHER","OP1",true),issue.exceptionNo()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> exceptions.captureGrantFailure(ctx,"OTHER",grant.grantNo()))
                .isInstanceOf(BizException.class);
        assertThat(policies.get(scope,template).heldCount()).isEqualTo(1);
        assertThat(policies.get(scope,template).issuedCount()).isZero();
        String sourceKey=id();
        var sourceCommand=new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand(issue.revision(),"只核实来源");
        var checked=exceptionRecovery.querySource(ops,issue.exceptionNo(),sourceCommand,sourceKey);
        assertThat(checked.status()).isEqualTo("OPEN");
        assertThat(checked.evidence().sourceStatus()).isEqualTo("COMMITTED");
        assertThat(exceptionRecovery.querySource(ops,issue.exceptionNo(),sourceCommand,sourceKey)).isEqualTo(checked);
        assertThat(policies.get(scope,template).issuedCount()).isZero();
        assertThat(policies.get(scope,template).heldCount()).isEqualTo(1);
        assertThatThrownBy(() -> exceptionRecovery.retryGrant(ops,issue.exceptionNo(),sourceCommand,sourceKey))
                .isInstanceOf(BizException.class);
        var retry=new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand(checked.revision(),"核实原付款后恢复");
        String key=id();
        // Simulate process loss after durable claim but before querying the source or committing the original grant.
        exceptionActions.begin(ops,issue.exceptionNo(),retry,key);
        assertThat(exceptions.get(ops,issue.exceptionNo()).status()).isEqualTo("PROCESSING");
        var resolved=exceptionRecovery.retryGrant(ops,issue.exceptionNo(),retry,key);
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(exceptionRecovery.retryGrant(ops,issue.exceptionNo(),retry,key)).isEqualTo(resolved);
        assertThat(policies.get(scope,template).issuedCount()).isEqualTo(1);
        assertThat(policies.get(scope,template).heldCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_exception_action WHERE exception_no=?",
                Integer.class,issue.exceptionNo())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reason FROM pay_ent_exception_action WHERE exception_no=? AND operation_kind='RETRY_GRANT'",
                String.class,issue.exceptionNo())).isEqualTo("核实原付款后恢复");
        assertThatThrownBy(() -> exceptionRecovery.retryGrant(ops,issue.exceptionNo(),
                new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand(issue.revision(),"更换原因"),key))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> exceptionRecovery.retryGrant(scope,issue.exceptionNo(),retry,id()))
                .isInstanceOf(BizException.class);
    }

    @Test void reconciliationPersistsDifferenceWithoutChangingBalanceAndReplaysOriginalRun() {
        coordinator.recover(ctx,order);
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=?,pay_channel='WECHAT',pay_trade_no='PAY1' "
                + "WHERE order_no=?",System.currentTimeMillis(),order);
        snapshots.recordPaidFromOrder(order); coordinator.recover(ctx,order);
        var ops=new CatalogScope("MAIN",scope.entityNo(),"OP1",true);
        var command=new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.CheckRights(
                java.time.LocalDate.of(2026,10,19),"UTC","",1);
        var clean=reconciliation.checkRights(ops,command,id());
        assertThat(clean.checkedCount()).isEqualTo(1); assertThat(clean.diffCount()).isZero();
        jdbc.update("UPDATE ent_instance SET remaining_units=29 WHERE entity_no=?",scope.entityNo());
        String key=id();
        var mismatch=reconciliation.checkRights(ops,command,key);
        assertThat(mismatch.diffCount()).isEqualTo(1);
        assertThat(reconciliation.checkRights(ops,command,key)).isEqualTo(mismatch);
        assertThat(reconciliation.get(ops,mismatch.reconNo())).isEqualTo(mismatch);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_recon_diff WHERE recon_no=?",Integer.class,
                mismatch.reconNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE entity_no=?",Integer.class,
                scope.entityNo())).isEqualTo(29);
        assertThatThrownBy(() -> reconciliation.checkRights(ops,
                new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.CheckRights(command.bizDate(),"UTC","",2),key))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> reconciliation.get(scope,mismatch.reconNo())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> reconciliation.get(new CatalogScope("MAIN","OTHER","OP1",true),mismatch.reconNo()))
                .isInstanceOf(BizException.class);
    }

    @Test void issuanceCountsCommittedUnitsAndDetectsWrongOwnerWithoutReissuing() {
        coordinator.recover(ctx,order);
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=?,pay_channel='WECHAT',pay_trade_no='PAY1' "
                + "WHERE order_no=?",System.currentTimeMillis(),order);
        snapshots.recordPaidFromOrder(order); coordinator.recover(ctx,order);
        var ops=new CatalogScope("MAIN",scope.entityNo(),"OP1",true);
        var check=new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check("ISSUANCE",null,
                java.time.LocalDate.of(2026,10,19),"UTC","",20);
        assertThat(reconciliation.check(ops,check,id()).diffCount()).isZero();
        jdbc.update("UPDATE ent_instance SET user_no='WRONG' WHERE entity_no=?",scope.entityNo());
        var bad=reconciliation.check(ops,check,id());
        assertThat(bad.checkedCount()).isEqualTo(1);
        assertThat(bad.diffCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?",Integer.class,
                scope.entityNo())).isEqualTo(1);
    }

    @Test void actualGrantDeliveryFailureAutomaticallyCreatesOneRecoverableException() {
        coordinator.recover(ctx,order);
        jdbc.update("UPDATE ord_order SET status='PAID',paid_at=?,pay_channel='WECHAT',pay_trade_no='PRIVATE_RECEIPT' "
                + "WHERE order_no=?",System.currentTimeMillis(),order);
        snapshots.recordPaidFromOrder(order);
        var grant=new EntitlementGrantPort.GrantCommand("G"+id(),command.sourceRef(),null,
                command.templateVersionNo(),"U1",scope.entityNo(),1,command.benefitSnapshotHash(),"PRIVATE_RECEIPT");
        grants.prepare(ctx,grant);
        var truth=sources.decision(ctx,grant.sourceRef());
        var commit=new EntitlementGrantPort.GrantDecision(grant.grantNo(),grant.sourceRef(),truth.decision());
        jdbc.execute("ALTER TABLE pay_ent_outbox ADD CONSTRAINT reject_grant_delivery "
                + "CHECK (event_type<>'ENTITLEMENT_GRANTED' OR aggregate_no<>'"+grant.grantNo()+"')");
        try {
            assertThatThrownBy(() -> grants.commit(ctx,commit)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> grants.commit(ctx,commit)).isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("ALTER TABLE pay_ent_outbox DROP CONSTRAINT reject_grant_delivery"); }
        var ops=new CatalogScope("MAIN",scope.entityNo(),"OP1",true);
        var records=exceptions.list(ops,null,"OPEN",1,20).records();
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().bizNo()).isEqualTo(grant.grantNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE "
                + "event_type='ENTITLEMENT_EXCEPTION_OPENED' AND aggregate_no=?",Integer.class,
                records.getFirst().exceptionNo())).isEqualTo(1);
        assertThat(grants.query(ctx,scope.entityNo(),grant.grantNo()).status())
                .isEqualTo(EntitlementGrantPort.State.PREPARED);
        var resolved=exceptionRecovery.retryGrant(ops,records.getFirst().exceptionNo(),
                new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand(0,"验证实际发放故障已解除"),id());
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(policies.get(scope,template).issuedCount()).isEqualTo(1);
        var history=exceptions.actions(ops,resolved.exceptionNo(),1,20);
        assertThat(history.records()).hasSize(1);
        assertThat(history.records().getFirst().status()).isEqualTo("DONE");
        assertThat(history.records().getFirst().operationKind()).isEqualTo("RETRY_GRANT");
        assertThat(json.writeValueAsString(history)).doesNotContain("PRIVATE_RECEIPT","requestKey","payloadHash",
                "responseSnapshot");
        assertThatThrownBy(() -> exceptions.actions(new CatalogScope("MAIN","OTHER","OP1",true),
                resolved.exceptionNo(),1,20)).isInstanceOf(BizException.class);
    }

    private void cancel() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE ord_order SET status='CANCELLED' WHERE order_no=?", order);
            snapshots.recordCancelledFromOrder(order);
        });
    }
}
