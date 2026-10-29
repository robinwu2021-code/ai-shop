package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.fulfillment.service.PickupService;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.FulfillmentQueryPort;
import ai.neargo.shop.support.OrderReceiptFixture;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService;
import ai.neargo.shop.trade.order.service.receipt.OrderReceiptCompleter;
import ai.neargo.shop.trade.service.MerchantOrderService;
import ai.neargo.shop.trade.service.OrderService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

/**
 * Existing payment transitions consume historical receipt fixtures; this does not test payment
 * initiation.
 */
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutManagedLifecycleFlowTest extends CheckoutFlowFixture {
    @Autowired private OrderService orders;
    @Autowired private MerchantOrderService merchantOrders;
    @Autowired private PaymentLifecycleService payments;
    @Autowired private TradeMappers.OrderMapper orderMapper;

    @Test
    void managedPaidTransitionDoesNotIssueLegacyVerificationCode() {
        var result = created();
        paymentFact(result);
        orders.markPaid(result.orderNo(), "WECHAT", "T-" + sku);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                result.orderNo()))
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT verify_code FROM ord_sub_order WHERE sub_order_no=?",
                                String.class,
                                result.subOrderNo()))
                .as("payment does not authorize legacy service verification")
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM trd_shipping_upload WHERE order_no=?",
                                Long.class,
                                result.orderNo()))
                .isZero();
    }

    @Test
    void oldMerchantOfflineShortcutCannotInventManagedReceipt() {
        var result = created();
        jdbc.update(
                "UPDATE ord_order SET status='WAIT_OFFLINE_PAY' WHERE order_no=?",
                result.orderNo());
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                merchantOrders.confirmOfflinePay(
                                                        entity, store, result.subOrderNo(), actor)))
                .isInstanceOf(BizException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                result.orderNo()))
                .isEqualTo("WAIT_OFFLINE_PAY");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT offline_confirmed_by FROM ord_order WHERE order_no=?",
                                String.class,
                                result.orderNo()))
                .isNull();
    }

    @Test
    void sharedOfflineTrustBranchCannotBypassManagedGuard() {
        var result = created();
        jdbc.update(
                "UPDATE ord_order SET status='WAIT_OFFLINE_PAY' WHERE order_no=?",
                result.orderNo());
        OrdOrder order =
                orderMapper.selectOne(
                        Wrappers.<OrdOrder>lambdaQuery()
                                .eq(OrdOrder::getOrderNo, result.orderNo()));
        assertThatThrownBy(() -> payments.requireConfirmed(order, "OFFLINE", "OFFLINE:" + actor))
                .isInstanceOf(BizException.class);
    }

    @Test
    void currentBuyerCanCancelUnpaidManagedOrderWithoutCompletingService() {
        var result = created();
        asBuyer(() -> orders.cancel(result.orderNo(), "buyer-cancel"));
        assertManagedCancelled(result);
    }

    @Test
    void ordinaryExpirySweepCanCloseUnpaidManagedOrderWithoutCompletingService() {
        var result = created();
        jdbc.update("UPDATE ord_order SET pay_deadline_at=1 WHERE order_no=?", result.orderNo());
        assertThat(orders.closeExpiredOrders(2)).isPositive();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                result.orderNo()))
                .isEqualTo("CLOSED");
        assertManagedCancelled(result);
    }

    private void assertManagedCancelled(CheckoutPort.CheckoutResult result) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_sub_order WHERE sub_order_no=?",
                                String.class,
                                result.subOrderNo()))
                .isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT completed_at FROM ord_sub_order WHERE sub_order_no=?",
                                Long.class,
                                result.subOrderNo()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_status_log WHERE sub_order_no=?"
                                        + " AND status='COMPLETED'",
                                Long.class,
                                result.subOrderNo()))
                .isZero();
    }

    private CheckoutPort.CheckoutResult created() {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        return asBuyer(() -> checkout.place(place(q, c)));
    }

    private void paymentFact(CheckoutPort.CheckoutResult result) {
        jdbc.update(
                "INSERT INTO"
                        + " stl_payment(payment_no,direction,order_no,user_no,pay_channel,amount_minor,"
                        + "currency,status,trade_no,created_at,updated_at)"
                        + " VALUES (?,'PAY',?,?,'WECHAT',303,'CNY','SUCCESS',?,NOW(),NOW())",
                "P-" + sku,
                result.orderNo(),
                buyer,
                "T-" + sku);
    }

    @Autowired private FulfillmentQueryPort fulfillment;
    @Autowired private PickupService pickups;
    @Autowired private OrderReceiptCompleter receipts;

    @Autowired
    @Qualifier("transactionManager")
    private PlatformTransactionManager manager;

    @Test
    void actualFulfillmentPickupCompletionRejectsManagedOrder() {
        var result = fulfilled(false);
        rejects(result, () -> fulfillment.complete(result.subOrderNo(), actor, "pickup"));
    }

    @Test
    void actualPickupVerificationCannotCompleteHistoricalManagedCode() {
        var result = fulfilled(false);
        String pickup = "pickup-" + sku;
        String code = "code-" + sku;
        jdbc.update(
                "UPDATE ord_sub_order SET pickup_no=?,verify_code=? WHERE sub_order_no=?",
                pickup,
                code,
                result.subOrderNo());
        BizContext.set(
                new BizContext(entity, Set.of(pickup), Set.of(), Set.of(store), store, true));
        try {
            rejects(result, () -> asBuyer(() -> pickups.verify(code, false)));
        } finally {
            BizContext.clear();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ful_verify_log WHERE sub_order_no=?",
                                Long.class,
                                result.subOrderNo()))
                .isZero();
    }

    @Test
    void actualMerchantDeliveredAndInterventionRejectManagedOrder() {
        var result = fulfilled(false);
        rejects(result, () -> merchantOrders.delivered(entity, store, result.subOrderNo()));
        rejects(
                result,
                () -> merchantOrders.intervene(result.subOrderNo(), "COMPLETED", "review", actor));
    }

    @Test
    void actualCurrentBuyerLegacyReceiptRejectsManagedOrder() {
        var result = fulfilled(false);
        rejects(result, () -> asBuyer(() -> orders.confirmReceipt(result.subOrderNo())));
    }

    @Test
    void actualAutoLegacyReceiptRejectsManagedOrder() {
        var result = fulfilled(false);
        rejects(
                result,
                () ->
                        receipts.completeAuto(
                                result.subOrderNo(), System.currentTimeMillis(), 15, 0));
    }

    @Test
    void actualPackageReceiptRollsBackShipmentWriteWhenManagedCompletionIsDenied() {
        var result = fulfilled(true);
        rejects(result, () -> asBuyer(() -> orders.confirmReceipt(result.subOrderNo())));
        assertShipmentUnchanged(result);
        rejects(
                result,
                () ->
                        receipts.completeAuto(
                                result.subOrderNo(), System.currentTimeMillis(), 15, 0));
        assertShipmentUnchanged(result);
    }

    @Test
    void actualSignedReceiptRejectsManagedServiceFulfillmentBeforeShipmentMutation() {
        var result = fulfilled(true);
        boolean changed =
                new TransactionTemplate(manager)
                        .execute(
                                status ->
                                        receipts.completeSigned(
                                                result.subOrderNo(),
                                                "SH-" + sku,
                                                System.currentTimeMillis(),
                                                "wx"));
        assertThat(changed).isFalse();
        assertShipmentUnchanged(result);
        unchanged(result);
    }

    private CheckoutPort.CheckoutResult fulfilled(boolean shipment) {
        var result = created();
        // Historical owner-table fulfillment facts deliberately exercise legacy completion
        // entrances.
        jdbc.update("UPDATE ord_order SET status='PAID' WHERE order_no=?", result.orderNo());
        jdbc.update(
                "UPDATE ord_sub_order SET status='FULFILLING' WHERE sub_order_no=?",
                result.subOrderNo());
        long at = System.currentTimeMillis() - 20L * 86400000;
        jdbc.update(
                "INSERT INTO"
                        + " ord_status_log(sub_order_no,status,event,operator_type,operator_no,label,at,created_at)"
                        + " VALUES (?,'FULFILLING','SHIPPED','MERCHANT',?,'historical',?,NOW())",
                result.subOrderNo(),
                actor,
                at);
        if (shipment) {
            jdbc.update(
                    "INSERT INTO"
                            + " ord_shipment(shipment_no,sub_order_no,ship_store_no,fulfillment,carrier,"
                            + "waybill_no,status,created_at,updated_at)"
                            + " VALUES (?,?,?,'EXPRESS','SF',?,'SHIPPED',?,NOW())",
                    "SH-" + sku,
                    result.subOrderNo(),
                    store,
                    "way-" + sku,
                    OrderReceiptFixture.physicalAt(at));
            jdbc.update(
                    "UPDATE ord_item SET shipment_no=?,shipped_qty=qty WHERE sub_order_no=?",
                    "SH-" + sku,
                    result.subOrderNo());
        }
        return result;
    }

    private void rejects(CheckoutPort.CheckoutResult result, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.MANAGED_SERVICE_COMPLETION_BLOCKED));
        unchanged(result);
    }

    private void unchanged(CheckoutPort.CheckoutResult result) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_sub_order WHERE sub_order_no=?",
                                String.class,
                                result.subOrderNo()))
                .isEqualTo("FULFILLING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=? AND"
                                        + " event_type LIKE '%COMPLETED%'",
                                Long.class, result.subOrderNo()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT completed_at FROM ord_sub_order WHERE sub_order_no=?",
                                Long.class,
                                result.subOrderNo()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_status_log WHERE sub_order_no=? AND"
                                        + " status='COMPLETED'",
                                Long.class,
                                result.subOrderNo()))
                .isZero();
    }

    private void assertShipmentUnchanged(CheckoutPort.CheckoutResult result) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_shipment WHERE sub_order_no=?",
                                String.class,
                                result.subOrderNo()))
                .isEqualTo("SHIPPED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT version FROM ord_shipment WHERE sub_order_no=?",
                                Long.class,
                                result.subOrderNo()))
                .isZero();
    }
}
