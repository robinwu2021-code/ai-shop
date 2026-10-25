package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.support.RefundScenarioPayments;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ai.neargo.shop.paybridge.EntitlementOrderCoordinator;
import ai.neargo.shop.paybridge.EntitlementRecoveryService;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;
import ai.neargo.shop.paybridge.EntitlementOrderUseDecisionConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.EntitlementOrderUseService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/** Actual core authorizations, payment history and pay holds; no source/decision stubs. */
@SpringBootTest(properties = {"shop.core.entitlement.authorization.enabled=true",
        "shop.pay.entitlement.use.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ent-order-coordinator;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementOrderCoordinatorFlowTest {
    @Autowired EntitlementOrderCoordinator coordinator;
    @Autowired EntitlementRecoveryService recovery;
    @Autowired EntitlementOrderUseService orderUses;
    @Autowired OrderService orderService;
    @Autowired PaymentLedgerService ledger;
    @Autowired SettlePort settlement;
    @Autowired TestPayGateway paymentChannel;
    @Autowired EntitlementAuthorizationService authorizations;
    @MockitoSpyBean EntitlementUsePort uses;
    @Autowired EntitlementUseSourcePort sources;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired EntitlementOrderUseDecisionConsumer consumer;
    private String order;
    private String sub;
    private String user;
    private String entity;
    private String store;
    private EntitlementContext context;
    private String instance;
    @BeforeEach
    void setup() {
        String id = UUID.randomUUID().toString();
        order = "OF-O" + id;
        sub = "OF-SO" + id;
        user = "OF-U" + id;
        entity = "OF-M" + id;
        store = "OF-S" + id;
        context = new EntitlementContext("MAIN", "PAY", "FACTS", "a".repeat(64));
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user);
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'owner facts','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, user);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'facts store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        jdbc.update("INSERT INTO ord_order(order_no,user_no,status,pay_amount,goods_amount,freight_amount,"
                + "discount_amount,currency,tenant_no,created_at,updated_at) VALUES (?,?,'WAIT_PAY',900,1000,0,100,"
                + "'CNY','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", order, user);
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                + "goods_amount,freight_amount,discount_amount,pay_amount,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,'WAIT_PAY','SERVICE',1000,0,100,900,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sub, order, user, entity, store);
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,qty,price,amount,"
                + "paid_share_minor,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,'GOODS1','SKU1','Care',1,1000,1000,1,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, order);
    }

    private SourceRef source() { return new SourceRef("CORE", "ORDER_USE", sub, null, null, null, 0); }
    private UseCommand command() {
        return command(new EntitlementDefinition.RedemptionScope("ALL", List.of()));
    }
    private UseCommand command(EntitlementDefinition.RedemptionScope scope) {
        instance = "IC" + UUID.randomUUID();
        var rule = new EntitlementDefinition("Care coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 100L,
                null, null, 0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                scope, List.of("TARGETED"));
        jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,"
                + "entity_no,kind,status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,"
                + "effective_from,effective_until,activation_at,tenant_no,created_at,updated_at,created_by,updated_by) "
                + "VALUES (?,'V1',?,?,?,?,'ORDER_COUPON','ACTIVE',?,1,1,1,0,1,"
                + "DATEADD('DAY',-1,CURRENT_TIMESTAMP),DATEADD('DAY',30,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'TEST','TEST')", instance, UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "") + "a".repeat(32), user, entity,
                json.writeValueAsString(rule));
        var fact = sources.resolve(context, source());
        var quote = coordinator.quote(context, order, source(), List.of(instance));
        var auth = authorizations.confirm("MAIN", user, order,
                new AuthorizationCommand(0, quote.quoteRef(), quote.selectionDigest(), UUID.randomUUID().toString()));
        return new UseCommand("OP" + UUID.randomUUID(), source(), user, entity, store, "CNY",
                AssetKind.ORDER_COUPON, instance, null, null, 1, 100L, fact.items(), auth.authorizationRef(),
                null, 0, null, quote.quoteRef());
    }
    @Test void anUnknownPayResponseKeepsTheOriginalDurableCommandAndCanResume() {
        var c = command();
        doThrow(BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE)).doCallRealMethod()
                .when(uses).prepare(any(), any());
        assertThat(coordinator.prepare(context, c).status()).isEqualTo(State.PROCESSING);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_ent_order_use WHERE operation_no=?", String.class,
                c.operationNo())).isEqualTo("PENDING");
        assertThat(uses.query(context, entity, c.operationNo()).status()).isEqualTo(State.NOT_FOUND);
        assertThat(coordinator.recover(context, entity, c.operationNo()).status()).isEqualTo(State.HELD);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_use_intent WHERE operation_no=?", Integer.class,
                c.operationNo())).isOne();
    }
    @Test void actualHeldCouponIsAppliedOnceAndPaymentSealPreventsRepricing() {
        var c = command();
        assertThat(coordinator.prepare(context, c).status()).isEqualTo(State.HELD);
        assertThat(coordinator.prepare(context, c).status()).isEqualTo(State.HELD);
        assertThat(jdbc.queryForObject("SELECT pay_amount FROM ord_order WHERE order_no=?", Long.class, order))
                .isEqualTo(800);
        assertThat(jdbc.queryForObject("SELECT paid_share_minor FROM ord_item WHERE order_no=?", Long.class, order))
                .isEqualTo(800);
        assertThat(orderUses.sealForPayment("MAIN", order).getPayAmount()).isEqualTo(800);
        String trade = RefundScenarioPayments.receivedForOrder(jdbc, ledger, paymentChannel, order);
        orderService.markPaid(order, "TEST", trade);
        String payload = jdbc.queryForObject("SELECT payload FROM sys_outbox WHERE aggregate_id=? "
                + "AND event_type='ENTITLEMENT_ORDER_USE_DECIDED'", String.class, c.operationNo());
        var event = new SysOutbox(); event.setPayload(payload);
        consumer.consume(event); consumer.consume(event);
        assertThat(coordinator.recover(context, entity, c.operationNo()).status()).isEqualTo(State.COMMITTED);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
    }
    @Test void failedTerminalOutboxRollsBackTheCorePaidDecisionWithoutReleasingThePayHold() {
        var c = command();
        coordinator.prepare(context, c);
        String trade = RefundScenarioPayments.receivedForOrder(jdbc, ledger, paymentChannel, order);
        jdbc.execute("ALTER TABLE sys_outbox ADD CONSTRAINT reject_ent_decision "
                + "CHECK (aggregate_id<>'" + c.operationNo() + "' OR event_type<>'ENTITLEMENT_ORDER_USE_DECIDED')");
        try {
            assertThatThrownBy(() -> orderService.markPaid(order, "TEST", trade))
                    .isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("ALTER TABLE sys_outbox DROP CONSTRAINT reject_ent_decision"); }
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?", String.class, order))
                .isEqualTo("WAIT_PAY");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_ent_order_use WHERE operation_no=?", String.class,
                c.operationNo())).isEqualTo("APPLIED");
        assertThat(uses.query(context, entity, c.operationNo()).status()).isEqualTo(State.HELD);
        orderService.markPaid(order, "TEST", trade);
        assertThat(coordinator.recover(context, entity, c.operationNo()).status()).isEqualTo(State.COMMITTED);
    }
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }
    @Test void zeroCashCheckoutMustKeepThePaidOrderAndItsEntitlementDecisionAtomic() {
        jdbc.update("UPDATE ord_order SET pay_amount=100,discount_amount=900 WHERE order_no=?", order);
        jdbc.update("UPDATE ord_sub_order SET pay_amount=100,discount_amount=900 WHERE order_no=?", order);
        var c = command();
        coordinator.prepare(context, c);
        var login = new LoginUser(Realm.CONSUMER, SubjectKind.USR, user, "Buyer", List.of(), List.of(), null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, List.of()));
        jdbc.execute("ALTER TABLE sys_outbox ADD CONSTRAINT reject_zero_decision CHECK (aggregate_id<>'"
                + c.operationNo() + "' OR event_type<>'ENTITLEMENT_ORDER_USE_DECIDED')");
        try { assertThatThrownBy(() -> orderService.pay(order, null)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE sys_outbox DROP CONSTRAINT reject_zero_decision"); }
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?", String.class, order))
                .isEqualTo("WAIT_PAY");
        assertThat(uses.query(context, entity, c.operationNo()).status()).isEqualTo(State.HELD);
        // The zero-cash receipt survived the failed core projection: retry that receipt, not collection.
        var receipt = settlement.successPayment(order);
        assertThat(receipt).isNotNull();
        assertThat(receipt.payChannel()).isEqualTo("FREE");
        assertThat(receipt.amountMinor()).isZero();
        assertThatThrownBy(() -> orderService.pay(order, null)).isInstanceOf(BizException.class);
        orderService.markPaid(order, receipt.payChannel(), receipt.tradeNo());
        assertThat(coordinator.recover(context, entity, c.operationNo()).status()).isEqualTo(State.COMMITTED);
    }
    @Test void scopedCouponPreservesTheFrozenPerLineDiscountInsteadOfRedistributingIt() {
        jdbc.update("UPDATE ord_order SET goods_amount=1500,pay_amount=1400 WHERE order_no=?", order);
        jdbc.update("UPDATE ord_sub_order SET goods_amount=1500,pay_amount=1400 WHERE order_no=?", order);
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,qty,price,amount,"
                + "paid_share_minor,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,'GOODS2','SKU2','Other',1,500,500,0,'MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, order);
        var sourceFact = sources.resolve(context, source());
        long unaffected = sourceFact.items().stream().filter(i -> i.itemNo().equals("GOODS2"))
                .findFirst().orElseThrow().amountMinor();
        var c = command(new EntitlementDefinition.RedemptionScope("GOODS", List.of("GOODS1")));
        assertThat(coordinator.prepare(context, c).status()).isEqualTo(State.HELD);
        assertThat(jdbc.queryForObject("SELECT paid_share_minor FROM ord_item WHERE order_no=? AND goods_no='GOODS2'",
                Long.class, order)).isEqualTo(unaffected);
        assertThat(jdbc.queryForObject("SELECT SUM(paid_share_minor) FROM ord_item WHERE order_no=?",
                Long.class, order))
                .isEqualTo(1300);
    }
    @Test void durableCancellationBeforeLatePrepareCreatesANegativePayFence() {
        var c = command();
        coordinator.stage(context, c);
        assertThatThrownBy(() -> orderUses.sealForPayment("MAIN", order)).isInstanceOf(BizException.class);
        jdbc.update("UPDATE ord_order SET pay_deadline_at=1 WHERE order_no=?", order);
        orderService.closeExpiredOrders(System.currentTimeMillis());
        assertThat(recovery.recover("MAIN",Kind.ORDER_USE,0,100).completed()).isGreaterThanOrEqualTo(1);
        assertThat(uses.query(context,entity,c.operationNo()).status()).isEqualTo(State.RELEASED);
        assertThat(uses.prepare(context, c).status()).isEqualTo(State.RELEASED);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
    }
    @Test void aCashPaymentSealRejectsANewCouponBeforeAnyPayReservation() {
        var c = command();
        orderUses.sealForPayment("MAIN", order);
        assertThatThrownBy(() -> coordinator.prepare(context, c)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
    }
    @Test void anExistingPaymentAttemptRejectsDiscountEvenIfTheOrderStillLooksUnpaid() {
        var c = command();
        jdbc.update("INSERT INTO stl_payment(payment_no,direction,order_no,user_no,pay_channel,amount_minor,currency,"
                + "status,tenant_no,created_at,updated_at) VALUES (?,'PAY',?,?,'WX',900,'CNY','PENDING','MAIN',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), order, user);
        assertThatThrownBy(() -> coordinator.prepare(context, c)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_ent_order_use WHERE order_no=?", Integer.class,
                order)).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",
                Integer.class, instance)).isZero();
    }
}
