package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.points.PointsPaymentSnapshotPort;
import ai.neargo.shop.spi.points.PointsRefundPort;
import ai.neargo.shop.spi.points.PointsRedeemPort;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import ai.neargo.shop.trade.order.points.OrderPointsBindingService;
import ai.neargo.shop.trade.order.points.PointsOrderPlacementCoordinator;
import ai.neargo.shop.trade.service.AfterSaleService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.api.biz.BizAfterSaleController;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.refund.service.RefundFinancialOwnerService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/** Actual source decision makers remain refund applicants across asynchronous recovery. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:refund-requester;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "shop.pay.refund.review=true"})
@ActiveProfiles("test")
class RefundRequesterIdentityFlowTest {
    @Autowired private PaymentPort payments;
    @Autowired private PaymentLedgerService ledger;
    @Autowired private TestPayGateway bank;
    @Autowired private BizAfterSaleController controller;
    @Autowired private RefundFinancialOwnerService owner;
    @Autowired private RefundService refunds;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AfterSaleService afterSales;
    @MockitoBean private PointsPaymentSnapshotPort pointsPayments;
    @MockitoBean private PointsRefundPort pointsRefunds;
    @MockitoBean private OrderPointsBindingService pointsBindings;

    @AfterEach void clearIdentity() {
        BizContext.clear();
        SecurityContextHolder.clearContext();
    }

    private record Fixture(String order, String afterSale, String origin) {}

    private Fixture fixture() {
        String order = "RQ-" + UUID.randomUUID().toString().substring(0, 8);
        String as = "AS-" + order;
        Long store = jdbc.queryForObject("SELECT id FROM mch_store ORDER BY id LIMIT 1", Long.class);
        payments.createPayment(new PaymentPort.InternalCreatePaymentCmd(PaymentPort.MALL_ORDER, order, order, store,
                "门店", "主体", "CN", 110, "CNY", "goods", System.currentTimeMillis() + 900_000, "AGGREGATED"));
        ledger.open(new SettlePort.PaymentOpen(order, "USER", null, "TEST", 110, "goods"));
        String trade = bank.placeOrder(order, 110);
        bank.markPaid(order);
        ledger.settle(new SettlePort.PaymentSettled(order, "TEST", trade, 110, System.currentTimeMillis(), "CNY"));
        jdbc.update("INSERT INTO ord_order(order_no,user_no,pay_amount,status,pay_channel,pay_trade_no,currency,"
                + "created_at,updated_at) VALUES(?,'USER',110,'PAID','TEST',?,'CNY',NOW(),NOW())", order, trade);
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,"
                + "pay_amount,goods_amount,freight_amount,open_after_sale_no,created_at,updated_at)"
                + " VALUES(?,?,'USER','MERCHANT','STORE','FULFILLING','STORE_PICKUP',110,100,10,?,NOW(),NOW())",
                order, order, as);
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,price,qty,amount,"
                + "paid_share_minor,created_at,updated_at) VALUES(?,?,'G','SKU','ITEM',50,2,100,100,NOW(),NOW())",
                order, order);
        long item = jdbc.queryForObject("SELECT id FROM ord_item WHERE sub_order_no=?", Long.class, order);
        jdbc.update("INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,"
                + "refund_minor,approved_minor,reason,created_at,updated_at)"
                + " VALUES(?,?,?,'USER','MERCHANT','REFUND_ONLY','APPLIED',110,110,'OTHER',NOW(),NOW())",
                as, order, order);
        jdbc.update("INSERT INTO ord_after_sale_item(after_sale_no,sub_order_no,item_id,qty,refund_minor,"
                + "created_at,updated_at) VALUES(?,?,?,2,100,NOW(),NOW())", as, order, item);
        jdbc.update("INSERT INTO stl_bill(settle_no,sub_order_no,order_no,entity_no,store_no,pay_merchant_no,"
                + "gross_minor,commission_minor,service_fee_minor,net_minor,status,funds_mode,created_at,updated_at)"
                + " VALUES(?,?,?,'MERCHANT','STORE','PM-TEST',110,0,0,110,'PENDING','DIRECT',NOW(),NOW())",
                order, order, order);
        return new Fixture(order, as, ledger.paymentNoOf(order).orElseThrow());
    }

    private static PayCaller ops(String who) {
        return new PayCaller(CallerType.OPS, 1L, Set.of(), who, null, null);
    }

    private void merchantLogin() {
        var login = LoginUser.consumer("STAFF-ORIGINAL", "employee").withRealm(Realm.MERCHANT);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, Set.of()));
        BizContext.set(new BizContext("MERCHANT", Set.of(), Set.of(), Set.of("STORE"), "STORE", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "CONFIRM_REFUND", "RETURN_RECEIVED", "RETURN_RECEIVED_LINES"})
    void merchantLoginReachesDecisionRefundAndApprovalAndCannotReviewItself(String action) {
        var f = fixture();
        merchantLogin();
        if ("CONFIRM_REFUND".equals(action)) {
            jdbc.update("UPDATE ord_after_sale SET status='WAIT_RECEIPT' WHERE after_sale_no=?", f.afterSale());
            controller.confirmRefund(f.afterSale(), null);
        } else if (action.startsWith("RETURN_RECEIVED")) {
            jdbc.update("UPDATE ord_after_sale SET type='RETURN_REFUND',status='RETURNING' WHERE after_sale_no=?",
                    f.afterSale());
            if ("RETURN_RECEIVED_LINES".equals(action)) {
                long item = jdbc.queryForObject("SELECT id FROM ord_item WHERE sub_order_no=?", Long.class, f.order());
                controller.confirmReturn(f.afterSale(), new BizAfterSaleController.ReceiveReq(false, "RCV-" + f.order(),
                        List.of(new BizAfterSaleController.ReceiveLineReq(item, 2, "SALEABLE"))));
            } else controller.confirmReturn(f.afterSale(), null);
        } else controller.approve(f.afterSale(), new BizAfterSaleController.ApproveReq("approved", null, "CASH"));
        assertThat(jdbc.queryForObject("SELECT operator_no FROM ord_status_log WHERE aggregate_type='AFTER_SALE'"
                + " AND aggregate_no=? AND status='REFUNDING' ORDER BY id LIMIT 1", String.class, f.afterSale()))
                .isEqualTo("STAFF-ORIGINAL");
        assertApplicantAndIsolation(f, "STAFF-ORIGINAL");
    }

    @Test void originalDecisionSurvivesRecoveryAndLaterDifferentOperators() {
        var f = fixture();
        jdbc.update("UPDATE ord_after_sale SET status='REFUNDING' WHERE after_sale_no=?", f.afterSale());
        decision(f, "OPS-ORIGINAL");
        var execution = execution(f);
        owner.request(execution);
        decision(f, "OPS-RECOVERY");
        owner.request(execution);
        assertApplicantAndIsolation(f, "OPS-ORIGINAL");
    }

    private OrdRefundExecution execution(Fixture f) {
        var execution = new OrdRefundExecution();
        execution.setExecutionNo("RF-" + f.afterSale());
        execution.setAfterSaleNo(f.afterSale()); execution.setSubOrderNo(f.order()); execution.setOrderNo(f.order());
        execution.setOriginPaymentNo(f.origin()); execution.setAmountMinor(110L); execution.setCurrency("CNY");
        execution.setReason("refund");
        return execution;
    }

    @Test void industryRefundUsesRefundDecisionMakerRatherThanOriginalOrderPlacer() {
        var f = fixture();
        jdbc.update("UPDATE ord_after_sale SET source_system='DINING',source_request_key=?,source_request_hash=?"
                + " WHERE after_sale_no=?", "REQUEST-" + f.order(), "a".repeat(64), f.afterSale());
        jdbc.update("INSERT INTO ord_industry_order_source(tenant_no,order_no,sub_order_no,entity_no,store_no,buyer_no,"
                + "actor_no,source_system,source_type,source_no,source_version,source_hash,quote_no,"
                + "confirmation_digest,"
                + "request_hash,order_mode,status,payment_out_trade_no,payment_state,created_at,updated_at)"
                + " VALUES('MAIN',?,?,'MERCHANT','STORE','USER','ORDER-PLACER','DINING','ORDER',?,1,'H','Q','D','R',"
                + "'TAKEAWAY','BOUND',?,'SUCCESS',NOW(),NOW())", f.order(), f.order(), f.order(), f.order());
        decision(f, "OPS-REFUND-DECISION");
        owner.request(execution(f));
        assertApplicantAndIsolation(f, "OPS-REFUND-DECISION");
    }

    @Test void anotherTenantDecisionCannotSupplyTheApplicant() {
        var f = fixture();
        decision(f, "FOREIGN-OPERATOR");
        jdbc.update("UPDATE ord_status_log SET tenant_no='FOREIGN' WHERE aggregate_no=?", f.afterSale());
        decision(f, "OPS-ORIGINAL");
        owner.request(execution(f));
        assertApplicantAndIsolation(f, "OPS-ORIGINAL");
    }

    @Test void historicalMerchantNumberIsNotInventedAsAPerson() {
        var f = fixture();
        decision(f, "MERCHANT");
        jdbc.update("UPDATE ord_status_log SET operator_type='MERCHANT' WHERE aggregate_no=?", f.afterSale());
        decision(f, "LATER-RECOVERY");
        owner.request(execution(f));
        assertThat(jdbc.queryForObject("SELECT requested_by FROM pay_refund WHERE biz_refund_no=?", String.class,
                f.afterSale())).isEqualTo("AFTER_SALE:" + f.afterSale());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM", "USER"})
    void automaticAndBuyerDecisionsKeepTheirOwnSourceIdentity(String kind) {
        var f = fixture();
        decision(f, "USER".equals(kind) ? "USER" : null);
        jdbc.update("UPDATE ord_status_log SET operator_type=? WHERE aggregate_no=?", kind, f.afterSale());
        owner.request(execution(f));
        assertThat(jdbc.queryForObject("SELECT requested_by FROM pay_refund WHERE biz_refund_no=?", String.class,
                f.afterSale())).isEqualTo("USER".equals(kind) ? "USER" : "SYSTEM:" + f.afterSale());
    }

    @Test void pointsCashPreparationRetainsAuthenticatedApplicantDuringWorkerRecovery() {
        var f = fixture();
        merchantLogin();
        jdbc.update("UPDATE ord_order SET points_integrated=1 WHERE order_no=?", f.order());
        jdbc.update("INSERT INTO ord_points_binding(binding_no,request_id,order_no,sub_order_no,store_no,phase,"
                + "payload_json) VALUES(?,?,?,?,?,'BOUND','{}')", "B:" + f.order(), "R:" + f.order(), f.order(),
                f.order(), "STORE");
        String trade = jdbc.queryForObject("SELECT trade_no FROM stl_payment WHERE payment_no=?",
                String.class, f.origin());
        var route = new PaymentRoute("MERCHANT", "STORE", "PM-TEST", "TEST", "SM", "PM:1", "DIRECT");
        var proof = new PointsPaymentSnapshotPort.VerifiedPayment(f.order(), "USER", "CNY", 110, "TEST", f.order(),
                trade, Instant.ofEpochMilli(1000), List.of(new SettlePort.PaymentStore(f.order(), route)));
        doReturn(Optional.of(proof)).when(pointsPayments).verified(f.order());
        var payment = new PointsRefundPort.PaymentSnapshot("DIRECT", "TEST", "MERCHANT", "STORE", "PM:1", "SM",
                trade, f.order(), 110);
        long item = jdbc.queryForObject("SELECT id FROM ord_item WHERE sub_order_no=?", Long.class, f.order());
        String earning = new ObjectMapper().writeValueAsString(
                new PointsOrderPlacementCoordinator.EarningSnapshot(2, 1, List.of()));
        var snapshot = new OrderPointsBindingService.Snapshot(100, 0, 0, 1, 1, earning, "{}", payment,
                List.of(new PointsRedeemPort.Item(String.valueOf(item), 100)));
        var admission = new OrderPointsBindingService.Admission("REQ", f.order(), f.order(), "USER", "MERCHANT",
                "STORE", "TYPE", "CNY", "ONLINE", 0, true, true, snapshot);
        doReturn(new OrderPointsBindingService.Binding("B", admission, OrderPointsBindingService.Phase.BOUND, 0, "H"))
                .when(pointsBindings).find(f.order());
        var prep = new PointsRefundPort.RefundPreparation("PREP:" + f.afterSale(), "TYPE", 1, "HASH", 0, 0, 0, 0,
                10, 100, 0, null, "PREPARED");
        doReturn(prep).when(pointsRefunds).prepare(any());
        doReturn(new PointsRefundPort.CashDecision(prep.preparationNo(), prep.inputHash(), 1, f.afterSale(), f.order(),
                "USER", "CNY", "TEST", 100, 10, null, payment)).when(pointsRefunds)
                .authorizeCash(prep.preparationNo(), prep.inputHash(), 1);
        controller.approve(f.afterSale(), new BizAfterSaleController.ApproveReq("approved", null, "CASH"));
        String originalPayment = jdbc.queryForObject("SELECT payment_no FROM stl_payment WHERE after_sale_no=?",
                String.class, f.afterSale());
        afterSales.resumeRefund(f.afterSale(), "DIFFERENT-RECOVERY-WORKER");
        assertThat(jdbc.queryForObject("SELECT payment_no FROM stl_payment WHERE after_sale_no=?", String.class,
                f.afterSale())).isEqualTo(originalPayment);
        assertApplicantAndIsolation(f, "STAFF-ORIGINAL");
    }

    private void decision(Fixture f, String operator) {
        jdbc.update("INSERT INTO ord_status_log(sub_order_no,aggregate_type,aggregate_no,status,operator_type,"
                + "operator_no,at,tenant_no,created_at)"
                + " VALUES(?,'AFTER_SALE',?,'REFUNDING','PLATFORM',?,1,'MAIN',NOW())",
                f.order(), f.afterSale(), operator);
    }

    private void assertApplicantAndIsolation(Fixture f, String operator) {
        String refund = jdbc.queryForObject("SELECT pay_refund_no FROM pay_refund WHERE biz_refund_no=?",
                String.class, f.afterSale());
        assertThat(jdbc.queryForObject("SELECT requested_by FROM pay_refund WHERE pay_refund_no=?",
                String.class, refund))
                .isEqualTo(operator);
        assertThat(jdbc.queryForObject("SELECT requested_by FROM pay_approval WHERE id=(SELECT pay_approval_id"
                + " FROM pay_refund WHERE pay_refund_no=?)", String.class, refund)).isEqualTo(operator);
        assertThatThrownBy(() -> refunds.review(ops(operator), refund, true, null)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_APPROVAL_SAME_PERSON);
        refunds.review(ops("INDEPENDENT-A"), refund, true, null);
        assertThatThrownBy(() -> refunds.review(ops(operator), refund, true, null)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PAY_APPROVAL_SAME_PERSON);
        refunds.review(ops("INDEPENDENT-B"), refund, true, null);
    }
}
