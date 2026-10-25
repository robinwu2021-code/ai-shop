package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.service.StockDispositionService;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.settle.PaymentOperationPort;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualMethod;
import ai.neargo.shop.spi.trade.OfflineReceiptPort.OfflineReceiptReq;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleMachine;
import ai.neargo.shop.trade.order.machine.order.OrderMachine;
import ai.neargo.shop.trade.order.machine.order.OrderStatus;
import ai.neargo.shop.trade.order.mapper.AfterSaleSlotMapper;
import ai.neargo.shop.trade.order.mapper.RefundExecutionMapper;
import ai.neargo.shop.trade.order.refund.model.RefundClosureFacts;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.policy.RefundClosurePolicy;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import ai.neargo.shop.trade.order.refund.service.PointsRefundResourceService;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;
import ai.neargo.shop.trade.order.refund.service.RefundObligationService;
import ai.neargo.shop.trade.order.refund.service.RefundPreparationService;
import ai.neargo.shop.trade.order.refund.service.RefundResourceService;
import ai.neargo.shop.trade.order.refund.service.RefundResultService;
import ai.neargo.shop.trade.order.refund.service.impl.PointsRefundResourceServiceImpl.Command;
import ai.neargo.shop.trade.order.refund.service.impl.RefundObligationServiceImpl;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.ManualServiceAdmissionService;
import ai.neargo.shop.trade.service.OfflineReceiptService;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.impl.AfterSaleServiceImpl;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Automatic financial selection and legacy financial writes cannot consume a manual source. */
@TestPropertySource(properties = "shop.inventory.lifecycle.version=2")
class ManualServiceFinancialIsolationIT extends CheckoutFlowFixture {
    @Autowired ManualServiceAdmissionPort manual;
    @Autowired SettleSourcePort settlement;
    @Autowired OrderService orders;
    @Autowired OfflineReceiptService receipts;
    @Autowired AfterSaleSlotMapper slots;
    @Autowired RefundPreparationService refunds;
    @Autowired RefundExecutionService executions;
    @Autowired RefundResultService refundResults;
    @Autowired ManualServiceAdmissionService manualOwner;
    @Autowired BasicRefundExecutionService cashRecovery;
    @Autowired RefundResourceService cashResources;
    @Autowired PointsRefundResourceService pointsResources;
    @Autowired ObjectMapper json;
    @Autowired AfterSaleService terminalOwner;
    @Autowired RefundExecutionMapper refundSources;

    @Autowired
    @Qualifier("transactionManager")
    PlatformTransactionManager coreTransactions;

    @MockitoSpyBean StockPort resourceStock;
    @MockitoSpyBean StockDispositionService platformStockOwner;
    @MockitoSpyBean StockDispositionPort stockBoundary;
    @MockitoSpyBean SettlePort payBoundary;
    @MockitoSpyBean PaymentOperationPort paymentBoundary;

    @Override
    protected <T> T asMerchant(Supplier<T> work) {
        return ManualServiceAdmissionIT.currentCashier(tokens, actor, work);
    }

    void forbidPay() {
        var violation = new AssertionError("manual source crossed financial owner boundary");
        doThrow(violation).when(payBoundary).successPayment(anyString());
        doThrow(violation).when(payBoundary).successPayment(anyString(), anyString(), anyString());
        doThrow(violation).when(payBoundary).refundOrigin(anyString(), anyString(), anyString());
        doThrow(violation).when(payBoundary).initPayment(any());
        doThrow(violation).when(payBoundary).openPayment(any());
        doThrow(violation).when(paymentBoundary).prepare(any());
        doThrow(violation).when(paymentBoundary).query(anyString());
        doThrow(violation).when(paymentBoundary).initiate(anyString());
        doThrow(violation).when(paymentBoundary).confirmOffline(any());
    }

    void admit(CheckoutPort.CheckoutResult order) {
        long revision =
                jdbc.queryForObject(
                        "SELECT version FROM ord_order WHERE order_no=?",
                        Long.class,
                        order.orderNo());
        asMerchant(
                () ->
                        manual.confirm(
                                scope,
                                new ConfirmManualReceipt(
                                        order.operation(),
                                        "cash-" + sku,
                                        revision,
                                        ManualMethod.CASH,
                                        101,
                                        "CNY",
                                        testClock.at.minusMillis(1).truncatedTo(ChronoUnit.MILLIS),
                                        null)));
    }

    @Override
    protected CheckoutPort.PrepareCheckoutReq request(String coupon) {
        return new CheckoutPort.PrepareCheckoutReq(
                new CheckoutPort.OperationRef("BEAUTY", "manual-" + sku),
                "prepare-" + sku,
                reference,
                1,
                "a".repeat(64),
                List.of(new CheckoutPort.CheckoutLineReq("service-line", sku, 1)),
                "OFFLINE",
                null,
                Map.of(),
                null);
    }

    CheckoutPort.CheckoutResult manualOrder() {
        jdbc.update(
                "INSERT INTO"
                        + " mch_qualification(qual_no,entity_no,qual_type,qual_name,qual_number,status,created_at,"
                        + "updated_at)"
                        + " VALUES (?,?,'BUSINESS_LICENSE','Current test qualification',"
                        + "'TEST-FIXTURE','VALID',NOW(),NOW())",
                "license-" + sku,
                entity);
        var q = assertDoesNotThrow(() -> asMerchant(() -> manual.prepare(scope, request(null))));
        var c = asBuyer(() -> confirm(q));
        return asBuyer(() -> checkout.place(place(q, c)));
    }

    @Test
    void manualOrderCannotReachPayOrRefund() {
        var o = manualOrder();
        forbidPay();
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                checkout.initiatePayment(
                                                        new CheckoutPort.InitiatePaymentReq(
                                                                o.operation(), "forbidden", null))))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> asBuyer(() -> orders.pay(o.orderNo(), "WECHAT")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> orders.markPaid(o.orderNo(), "OFFLINE", "unexpected"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                receipts.confirm(
                                                        scope,
                                                        new OfflineReceiptReq(
                                                                o.operation(),
                                                                "old-receipt",
                                                                "CASH",
                                                                101,
                                                                "CNY",
                                                                null,
                                                                testClock
                                                                        .at
                                                                        .minusMillis(1)
                                                                        .truncatedTo(
                                                                                ChronoUnit.MILLIS),
                                                                "cash"))))
                .isInstanceOf(BizException.class);
        assertThat(slots.occupy(o.subOrderNo(), "forbidden-aftersale")).isZero();
        // Explicit contradictory imported after-sale fixture must be denied before origin-payment
        // IO.
        String afterSale = "manual-as-" + sku;
        jdbc.update(
                "INSERT INTO"
                        + " ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,reason,"
                        + "refund_minor,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'REFUND_ONLY','REFUNDING','OTHER',101,NOW(),NOW())",
                afterSale,
                o.subOrderNo(),
                o.orderNo(),
                buyer,
                entity);
        jdbc.update(
                "UPDATE ord_sub_order SET open_after_sale_no=? WHERE sub_order_no=?",
                afterSale,
                o.subOrderNo());
        assertThatThrownBy(() -> refunds.prepare(afterSale, new OrderActor(entity, store, actor)))
                .isInstanceOf(BizException.class);
        // Imported contradictory execution rows must be fenced before replay, ledger or closure.
        String execution = "manual-ex-" + sku;
        jdbc.update(
                "INSERT INTO ord_refund_execution(execution_no,after_sale_no,sub_order_no,order_no,"
                        + "amount_minor,currency,plan_json,stock_needs_json,status,phase,next_attempt_at,"
                        + "resource_status,closure_version,closure_status,created_at,updated_at) VALUES"
                        + " (?,?,?,?,101,'CNY','{}','[]','SUCCEEDED','CASH',0,'DONE',2,'PENDING',NOW(),NOW())",
                execution,
                afterSale,
                o.subOrderNo(),
                o.orderNo());
        assertThatThrownBy(
                        () ->
                                refundResults.apply(
                                        new RefundDispatchResult(
                                                execution,
                                                "unexpected",
                                                DispatchStatus.SUCCEEDED,
                                                null,
                                                false,
                                                testClock.at.toEpochMilli())))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> refundResults.closePending(execution))
                .isInstanceOf(BizException.class);
        jdbc.update(
                "INSERT INTO"
                        + " ord_points_refund_execution(after_sale_no,sub_order_no,state,"
                        + "input_hash,payload_json,tenant_no)"
                        + " VALUES (?,?,'PREPARED',?,'{}','MAIN')",
                afterSale,
                o.subOrderNo(),
                "a".repeat(64));
        assertThatThrownBy(
                        () ->
                                executions.prepare(
                                        afterSale, new RefundPlan(o.subOrderNo(), List.of(), 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MANUAL_SERVICE_FINANCIAL_CONFLICT");
        assertThatThrownBy(() -> executions.beginCash(afterSale))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MANUAL_SERVICE_FINANCIAL_CONFLICT");
        assertThatThrownBy(() -> executions.commit(afterSale))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MANUAL_SERVICE_FINANCIAL_CONFLICT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM ord_points_refund_execution WHERE"
                                        + " after_sale_no=?",
                                String.class,
                                afterSale))
                .isEqualTo("PREPARED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(refunded_qty+refunded_minor) FROM ord_item WHERE"
                                        + " sub_order_no=?",
                                Long.class,
                                o.subOrderNo()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                        + " operation_no=?",
                                Long.class,
                                o.operation().operationNo()))
                .isZero();
    }

    @Test
    void manualPaidIsExcludedBeforeSettlementPageLimit() {
        var o = manualOrder();
        admit(o);
        forbidPay();
        jdbc.update("UPDATE ord_order SET paid_at=1 WHERE order_no=?", o.orderNo());
        // Named historical funded-row fixture tests reader pagination, not a claim of actual Pay
        // success.
        String control = "funded-control-" + sku;
        String child = "funded-child-" + sku;
        jdbc.update(
                "INSERT INTO"
                        + " ord_order(order_no,user_no,status,pay_amount,currency,paid_at,created_at,updated_at)"
                        + " VALUES (?,?,'PAID',101,'CNY',2,NOW(),NOW())",
                control,
                buyer);
        jdbc.update(
                "INSERT INTO"
                        + " ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,pay_amount,"
                        + "created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'FULFILLING',101,NOW(),NOW())",
                child,
                control,
                buyer,
                entity,
                store);
        var page = settlement.paidSubOrdersSince(0, 1);
        assertThat(page)
                .extracting(SettleSourcePort.PaidSubOrder::subOrderNo)
                .containsExactly(child);
        assertThat(page).noneMatch(row -> row.subOrderNo().equals(o.subOrderNo()));
    }

    @Test
    void manualSourceIsNeverSettlementReady() {
        var o = manualOrder();
        admit(o);
        forbidPay();
        assertThat(settlement.settleSourcesOf(o.orderNo())).isEmpty();
        assertThat(settlement.settleReadiness(List.of(o.subOrderNo()))).isEmpty();
    }

    @Test
    void unexpectedPaySuccessCannotRepairManualOrder() {
        var o = manualOrder();
        long originalRevision =
                jdbc.queryForObject(
                        "SELECT version FROM ord_order WHERE order_no=?", Long.class, o.orderNo());
        // Inject actual owner unavailability: the HOST adapter reports UNKNOWN, never a fake
        // success.
        doThrow(new IllegalStateException("injected owner unavailable"))
                .when(platformStockOwner)
                .observeOriginal(any());
        admit(o);
        doCallRealMethod().when(platformStockOwner).observeOriginal(any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT phase FROM ord_manual_service_admission WHERE order_no=?",
                                String.class,
                                o.orderNo()))
                .isEqualTo("STOCK_PENDING");
        forbidPay();
        var before =
                jdbc.queryForMap(
                        "SELECT status,version,pay_channel,pay_trade_no,receipt_provenance FROM"
                                + " ord_order WHERE order_no=?",
                        o.orderNo());
        assertThatThrownBy(() -> orders.markPaid(o.orderNo(), "WECHAT", "unexpected-pay-success"))
                .isInstanceOf(BizException.class);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT status,version,pay_channel,pay_trade_no,receipt_provenance"
                                        + " FROM ord_order WHERE order_no=?",
                                o.orderNo()))
                .isEqualTo(before);
        assertThat(settlement.notPaidOrders(List.of(o.orderNo()))).contains(o.orderNo());
        var observations = observations(o);
        var stockSnapshot =
                jdbc.queryForMap(
                        "SELECT stock_outcome,stock_state,stock_observation_hash "
                                + "FROM ord_manual_service_admission WHERE order_no=?",
                        o.orderNo());
        var replay =
                new ConfirmManualReceipt(
                        o.operation(),
                        "cash-" + sku,
                        originalRevision,
                        ManualMethod.CASH,
                        101,
                        "CNY",
                        testClock.at.minusMillis(1).truncatedTo(ChronoUnit.MILLIS),
                        null);
        clearInvocations(stockBoundary);
        var forbidden = new AssertionError("contaminated manual source called stock owner");
        doThrow(forbidden).when(stockBoundary).observeOriginal(any());
        doThrow(forbidden).when(stockBoundary).confirmOriginal(any());
        // Explicit contradictory local financial fixtures exercise both contamination owners.
        jdbc.update(
                "INSERT INTO"
                        + " ord_checkout_payment_request(caller,operation_no,payment_operation_no,order_no,"
                        + "sub_order_no,buyer_no,entity_no,store_no,actor_no,actor_realm,actor_kind,action,"
                        + "request_key_hash,request_hash,request_json,payment_request_json,created_at,updated_at)"
                        + " VALUES"
                        + " ('BEAUTY',?,?,?,?,?,?,?,?,'CONSUMER','USR','INITIATE_PAYMENT',?,?,'{}','{}',NOW(),NOW())",
                o.operation().operationNo(),
                "import-" + sku,
                o.orderNo(),
                o.subOrderNo(),
                buyer,
                entity,
                store,
                buyer,
                "c".repeat(64),
                "d".repeat(64));
        rejectContaminated(o, replay);
        assertThat(observations(o)).isEqualTo(observations);
        jdbc.update(
                "DELETE FROM ord_checkout_payment_request WHERE payment_operation_no=?",
                "import-" + sku);
        jdbc.update(
                "INSERT INTO"
                        + " ord_payment_resolution(pay_no,payment_no,user_no,amount_minor,currency,pay_channel,"
                        + "trade_no,reason,status,created_at,updated_at)"
                        + " VALUES"
                        + " (?,?,?,101,'CNY','IMPORT_FIXTURE','not-real-Pay',"
                        + "'SOURCE_MODE_CONFLICT','PENDING',NOW(),NOW())",
                o.orderNo(),
                "imported-pay-" + sku,
                buyer);
        rejectContaminated(o, replay);
        assertThat(observations(o)).isEqualTo(observations);
        jdbc.update(
                "UPDATE ord_manual_service_admission SET next_retry_at=DATE_SUB(NOW(),INTERVAL 1"
                        + " SECOND) WHERE order_no=?",
                o.orderNo());
        assertThat(manualOwner.recoverPending(100)).isGreaterThanOrEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT phase,reason_code FROM ord_manual_service_admission WHERE"
                                        + " order_no=?",
                                o.orderNo()))
                .containsEntry("phase", "SUSPENDED")
                .containsEntry("reason_code", "SOURCE_MODE_CONFLICT");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT stock_outcome,stock_state,stock_observation_hash "
                                        + "FROM ord_manual_service_admission WHERE order_no=?",
                                o.orderNo()))
                .isEqualTo(stockSnapshot);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_manual_service_admission_action "
                                        + "WHERE order_no=? AND action='ADMISSION_CHANGED'"
                                        + " AND reason_code='SOURCE_MODE_CONFLICT'",
                                Long.class,
                                o.orderNo()))
                .isEqualTo(1);
        assertThat(observations(o)).isEqualTo(observations);
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                o.orderNo()))
                .containsExactly("LOCKED");
        verify(stockBoundary, never()).observeOriginal(any());
        verify(stockBoundary, never()).confirmOriginal(any());
    }

    private void rejectContaminated(
            CheckoutPort.CheckoutResult order, ConfirmManualReceipt replay) {
        assertThatThrownBy(() -> asMerchant(() -> manual.confirm(scope, replay)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_PAYMENT_CONFLICT));
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                manual.query(
                                                        scope,
                                                        new ManualLookup(
                                                                order.operation(),
                                                                null,
                                                                replay.requestKey()))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_PAYMENT_CONFLICT));
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                manual.source(
                                                        scope,
                                                        new CheckoutPort.SourceLookup(
                                                                order.operation(),
                                                                order.subOrderNo()))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure ->
                                assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_PAYMENT_CONFLICT));
    }

    @Test
    void importedCashRecoveryRejectsManualBeforePayOrResourceEffects() {
        var order = manualOrder();
        admit(order);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("ALLOCATED");
        String afterSale = importAfterSale(order);
        String no = importCashExecution(order, afterSale);
        var ownerBefore = originalStock(order);
        var commandBefore = cashCommand(no);
        forbidRefundEffects();
        cashSourceConflict(no, () -> cashRecovery.recover(no));
        jdbc.update(
                "UPDATE ord_refund_execution SET phase='REVERSAL',next_attempt_at=0,"
                        + "error_code=NULL,resource_error_code=NULL WHERE execution_no=?",
                no);
        cashSourceConflict(no, () -> cashRecovery.dispatch(no));
        jdbc.update(
                "UPDATE ord_refund_execution SET error_code=NULL,resource_error_code=NULL WHERE execution_no=?",
                no);
        cashSourceConflict(
                no,
                () ->
                        cashRecovery.applyResult(
                                new RefundDispatchResult(
                                        no,
                                        "unverified",
                                        DispatchStatus.SUCCEEDED,
                                        null,
                                        false,
                                        testClock.at.toEpochMilli())));
        jdbc.update(
                "UPDATE ord_refund_execution SET status='SUCCEEDED',phase='CASH',resource_status='PENDING'"
                        + ",error_code=NULL,resource_error_code=NULL WHERE execution_no=?",
                no);
        cashSourceConflict(no, () -> cashRecovery.recover(no));
        jdbc.update(
                "UPDATE ord_refund_execution SET error_code=NULL,resource_error_code=NULL WHERE execution_no=?",
                no);
        cashSourceConflict(no, () -> cashResources.recover(no));
        assertThat(originalStock(order)).isEqualTo(ownerBefore);
        assertThat(cashCommand(no)).isEqualTo(commandBefore);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT resource_status FROM ord_refund_execution WHERE execution_no=?",
                                String.class,
                                no))
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT error_code FROM ord_refund_execution WHERE execution_no=?",
                                String.class,
                                no))
                .isEqualTo("REFUND_EXECUTION_CONFLICT");
        verify(payBoundary, never()).queryRefundExecution(any(RefundRequest.class));
        verify(payBoundary, never()).prepareRefundReversal(any(), anyBoolean());
        verify(payBoundary, never()).requestRefund(any());
        verify(resourceStock, never()).releaseAllocated(anyString(), anyString());
        verify(resourceStock, never()).restoreOriginal(anyString(), anyString(), any());
    }

    @Test
    void importedStockObligationRejectsManualBeforeOriginalReleaseOrRestore() throws Exception {
        var order = manualOrder();
        admit(order);
        String afterSale = importAfterSale(order);
        importStockObligation(order, afterSale);
        var ownerBefore = originalStock(order);
        var taskBefore = taskCommand(afterSale);
        forbidRefundEffects();
        sourceConflict(() -> pointsResources.recover(afterSale));
        sourceConflict(() -> pointsResources.recover(afterSale));
        assertThat(taskCommand(afterSale)).isEqualTo(taskBefore);
        assertThat(originalStock(order)).isEqualTo(ownerBefore);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT state,authority_proof,error_code FROM ord_refund_obligation"
                                        + " WHERE execution_no=?",
                                "PRF-" + afterSale))
                .containsEntry("state", "UNKNOWN")
                .containsEntry("authority_proof", null)
                .containsEntry("error_code", "REFUND_EXECUTION_CONFLICT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT stock_restored FROM ord_after_sale WHERE after_sale_no=?",
                                Integer.class,
                                afterSale))
                .isNotEqualTo(1);
        verify(resourceStock, never()).releaseAllocated(anyString(), anyString());
        verify(resourceStock, never()).restoreOriginal(anyString(), anyString(), any());
        verify(payBoundary, never()).queryRefundExecution(any(RefundRequest.class));
    }

    @Test
    void fundedRefundRecoveryStillDispatchesAndReleasesOriginalOutsideCoreLocks() throws Exception {
        var quote = asMerchant(() -> checkout.prepare(scope, super.request(null)));
        var consent = asBuyer(() -> confirm(quote));
        var order = asBuyer(() -> checkout.place(place(quote, consent)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT admission_kind FROM ord_order WHERE order_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("LEGACY_FUNDED");
        jdbc.update("UPDATE ord_order SET status='PAID' WHERE order_no=?", order.orderNo());
        jdbc.update(
                "UPDATE ord_sub_order SET status='WAIT_FULFILL' WHERE sub_order_no=?",
                order.subOrderNo());
        resourceStock.confirm(order.orderNo(), 2);
        String afterSale = importAfterSale(order);
        String no = importCashExecution(order, afterSale);
        var queries = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            queries.incrementAndGet();
                            return new RefundDispatchResult(
                                    no, null, DispatchStatus.ACCEPTED, null, true, null);
                        })
                .when(payBoundary)
                .queryRefundExecution(any(RefundRequest.class));
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return true;
                        })
                .when(payBoundary)
                .prepareRefundReversal(any(), anyBoolean());
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return new RefundDispatchResult(
                                    no, null, DispatchStatus.ACCEPTED, null, true, null);
                        })
                .when(payBoundary)
                .requestRefund(any());
        cashRecovery.recover(no);
        assertThat(queries).hasValue(1);
        jdbc.update(
                "UPDATE ord_refund_execution SET phase='REVERSAL',next_attempt_at=0 WHERE execution_no=?",
                no);
        cashRecovery.dispatch(no);
        verify(payBoundary).prepareRefundReversal(any(), anyBoolean());
        verify(payBoundary).requestRefund(any());
        cashRecovery.applyResult(
                new RefundDispatchResult(
                        no,
                        "unverified",
                        DispatchStatus.SUCCEEDED,
                        null,
                        false,
                        testClock.at.toEpochMilli()));
        assertThat(queries).hasValue(2);
        var obligationStage = new AtomicBoolean(false);
        doAnswer(
                        invocation -> {
                            assertSourceLocksReleased(
                                    order.subOrderNo(),
                                    no,
                                    obligationStage.get() ? afterSale : null);
                            return invocation.callRealMethod();
                        })
                .when(resourceStock)
                .releaseAllocated(anyString(), anyString());
        jdbc.update(
                "UPDATE ord_refund_execution SET status='SUCCEEDED',resource_status='PENDING',phase='CASH'"
                        + " WHERE execution_no=?",
                no);
        cashResources.recover(no);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT resource_status FROM ord_refund_execution WHERE execution_no=?",
                                String.class,
                                no))
                .isEqualTo("DONE");
        var released = originalStock(order);
        importStockObligation(order, afterSale);
        obligationStage.set(true);
        pointsResources.recover(afterSale);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT state,authority_proof FROM ord_refund_obligation WHERE execution_no=?",
                                "PRF-" + afterSale))
                .containsEntry("state", "SUCCEEDED")
                .containsEntry("authority_proof", "STOCK_RESTORE:PRF-" + afterSale);
        assertThat(originalStock(order)).isEqualTo(released);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("RELEASED");
    }

    @Test
    void fullPointsRefundTerminalStillRecoversPreparedOriginalStock() throws Exception {
        AfterSaleServiceImpl terminal = AopTestUtils.getUltimateTargetObject(terminalOwner);
        actualTerminalOrigin(terminalOwner, AfterSaleServiceImpl.class);
        actualTerminalOrigin(
                ReflectionTestUtils.getField(terminal, "statusWriter"), TradeStatusWriter.class);
        var obligations =
                (RefundObligationService)
                        ReflectionTestUtils.getField(terminal, "refundObligations");
        actualTerminalOrigin(obligations, RefundObligationServiceImpl.class);
        canonicalClassOrigin(OrderMachine.class);
        canonicalClassOrigin(AfterSaleMachine.class);
        var policy = new RefundClosurePolicy();
        actualTerminalOrigin(policy, RefundClosurePolicy.class);
        var quote = asMerchant(() -> checkout.prepare(scope, super.request(null)));
        var consent = asBuyer(() -> confirm(quote));
        var order = asBuyer(() -> checkout.place(place(quote, consent)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT admission_kind FROM ord_order WHERE order_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("LEGACY_FUNDED");
        // Financial authority completion is seeded; this test exercises real terminal/stock owners.
        jdbc.update("UPDATE ord_order SET status='PAID' WHERE order_no=?", order.orderNo());
        jdbc.update(
                "UPDATE ord_sub_order SET status='WAIT_FULFILL' WHERE sub_order_no=?",
                order.subOrderNo());
        resourceStock.confirm(order.orderNo(), 2);
        String afterSale = importAfterSale(order);
        long item =
                jdbc.queryForObject(
                        "SELECT id FROM ord_item WHERE sub_order_no=?",
                        Long.class,
                        order.subOrderNo());
        int qty = jdbc.queryForObject("SELECT qty FROM ord_item WHERE id=?", Integer.class, item);
        var plan =
                new RefundPlan(
                        order.subOrderNo(), List.of(new RefundLine(item, qty, qty * 101L)), 0);
        String payload = json.writeValueAsString(plan);
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(payload.getBytes(StandardCharsets.UTF_8)));
        jdbc.update(
                "UPDATE ord_after_sale SET refund_minor=? WHERE after_sale_no=?",
                plan.totalMinor(),
                afterSale);
        jdbc.update(
                "INSERT INTO ord_points_refund_execution(after_sale_no,sub_order_no,state,input_hash,"
                        + "payload_json,tenant_no,closure_version,net_cash_minor,cash_payment_no)"
                        + " VALUES (?,?,'COMMITTED',?,?,'MAIN',2,?,?)",
                afterSale,
                order.subOrderNo(),
                hash,
                payload,
                plan.totalMinor(),
                "R2-SEEDED-CASH-" + sku);
        jdbc.update(
                "INSERT INTO ord_refund_obligation(execution_no,kind,source_no,input_hash,payload_json,"
                        + "state,authority_proof) VALUES (?,'COUPON',?,?,'{}','NOT_REQUIRED','R2_SEEDED_POLICY')",
                "PRF-" + afterSale,
                order.orderNo(),
                "a".repeat(64));
        var tx = new TransactionTemplate(coreTransactions);
        tx.executeWithoutResult(
                status ->
                        DataScopeContext.executeWithoutScope(
                                () -> {
                                    var child = refundSources.lockSub(order.subOrderNo());
                                    assertThat(child.getStatus()).isEqualTo("WAIT_FULFILL");
                                    var current = refundSources.lockAfterSale(afterSale);
                                    pointsResources.prepare(current, plan);
                                    return null;
                                }));
        var commandBefore =
                jdbc.queryForMap(
                        "SELECT input_hash,payload_json FROM ord_refund_obligation"
                                + " WHERE execution_no=? AND kind='STOCK'",
                        "PRF-" + afterSale);
        var command = json.readValue((String) commandBefore.get("payload_json"), Command.class);
        assertThat(command.unshipped()).isTrue();
        assertThat(command.full()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("ALLOCATED");
        // Match the committed full-refund counters before the production terminal owner runs.
        jdbc.update(
                "UPDATE ord_item SET refunded_qty=qty WHERE sub_order_no=?", order.subOrderNo());
        jdbc.update(
                "UPDATE ord_sub_order SET refunded_minor=pay_amount WHERE sub_order_no=?",
                order.subOrderNo());
        tx.executeWithoutResult(
                status ->
                        DataScopeContext.executeWithoutScope(
                                () -> {
                                    var child = refundSources.lockSub(order.subOrderNo());
                                    var current = refundSources.lockAfterSale(afterSale);
                                    var lines = refundSources.items(order.subOrderNo());
                                    long ordered =
                                            lines.stream().mapToLong(line -> line.getQty()).sum();
                                    long terminated =
                                            lines.stream()
                                                    .mapToLong(line -> line.getRefundedQty())
                                                    .sum();
                                    var decision =
                                            policy.decide(
                                                    new RefundClosureFacts(
                                                            ordered,
                                                            0,
                                                            terminated,
                                                            child.getPayAmount(),
                                                            child.getRefundedMinor(),
                                                            obligations.settled(
                                                                    "PRF-" + afterSale)));
                                    assertThat(decision.targetOrderStatus())
                                            .isEqualTo(OrderStatus.REFUNDED);
                                    ReflectionTestUtils.invokeMethod(
                                            terminal,
                                            "settleRefundTerminal",
                                            current,
                                            "R2 seeded confirmed refund",
                                            plan.totalMinor(),
                                            decision.targetOrderStatus() == OrderStatus.REFUNDED);
                                    return null;
                                }));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_sub_order WHERE sub_order_no=?",
                                String.class,
                                order.subOrderNo()))
                .isEqualTo("REFUNDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_after_sale WHERE after_sale_no=?",
                                String.class,
                                afterSale))
                .isEqualTo("REFUNDED");
        var dispatches = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            assertSourceLocksReleased(order.subOrderNo(), null, afterSale);
                            dispatches.incrementAndGet();
                            return invocation.callRealMethod();
                        })
                .when(resourceStock)
                .releaseAllocated(anyString(), anyString());
        assertDoesNotThrow(
                () -> pointsResources.recover(afterSale),
                "actual full-refund terminal progression must preserve prepared original stock eligibility");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT state,authority_proof,error_code FROM ord_refund_obligation"
                                        + " WHERE execution_no=? AND kind='STOCK'",
                                "PRF-" + afterSale))
                .containsEntry("state", "SUCCEEDED")
                .containsEntry("authority_proof", "STOCK_RESTORE:PRF-" + afterSale)
                .containsEntry("error_code", null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT stock_restored FROM ord_after_sale WHERE after_sale_no=?",
                                Integer.class,
                                afterSale))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM prd_stock_lock WHERE lock_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("RELEASED");
        assertThat(jdbc.queryForMap("SELECT stock,locked_stock FROM prd_sku WHERE sku_no=?", sku))
                .containsEntry("stock", 100)
                .containsEntry("locked_stock", 0);
        var released = originalStock(order);
        pointsResources.recover(afterSale);
        assertThat(dispatches).hasValue(1);
        assertThat(originalStock(order)).isEqualTo(released);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT input_hash,payload_json FROM ord_refund_obligation"
                                        + " WHERE execution_no=? AND kind='STOCK'",
                                "PRF-" + afterSale))
                .isEqualTo(commandBefore);
    }

    private static void actualTerminalOrigin(Object owner, Class<?> expected) {
        assertThat(owner).isNotNull();
        var type = AopUtils.getTargetClass(owner);
        assertThat(type).isEqualTo(expected);
        canonicalClassOrigin(type);
    }

    private static void canonicalClassOrigin(Class<?> type) {
        String origin = type.getProtectionDomain().getCodeSource().getLocation().toString();
        assertThat(origin).contains("/beauty-data/ai-shop-codex/").contains("/target/classes/");
        System.out.println("TASK64_R2_CODE_SOURCE " + type.getName() + " " + origin);
    }

    private void assertSourceLocksReleased(
            String subOrderNo, String executionNo, String afterSaleNo) {
        try {
            var raw = dataSource.unwrap(HikariDataSource.class);
            assertThat(raw).isNotNull();
            try (var connection = raw.getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
                assertThat(DataSourceUtils.isConnectionTransactional(connection, dataSource))
                        .isFalse();
                connection.setAutoCommit(false);
                try {
                    lockProbe(
                            connection,
                            "SELECT id FROM ord_sub_order WHERE sub_order_no=? FOR UPDATE NOWAIT",
                            subOrderNo);
                    if (executionNo != null) {
                        lockProbe(
                                connection,
                                "SELECT id FROM ord_refund_execution WHERE execution_no=? FOR UPDATE NOWAIT",
                                executionNo);
                    }
                    if (afterSaleNo != null) {
                        lockProbe(
                                connection,
                                "SELECT id FROM ord_refund_obligation WHERE execution_no=?"
                                        + " AND kind='STOCK' FOR UPDATE NOWAIT",
                                "PRF-" + afterSaleNo);
                        lockProbe(
                                connection,
                                "SELECT id FROM ord_points_refund_execution WHERE after_sale_no=?"
                                        + " FOR UPDATE NOWAIT",
                                afterSaleNo);
                    }
                } finally {
                    connection.rollback();
                }
            }
        } catch (SQLException failure) {
            throw new AssertionError(
                    "Core source or claim lock remains held at actual stock IO", failure);
        }
    }

    private static void lockProbe(Connection connection, String sql, String key)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setQueryTimeout(3);
            try (var result = statement.executeQuery()) {
                assertThat(result.next())
                        .as("actual source/claim row exists for independent lock probe")
                        .isTrue();
                assertThat(result.next()).isFalse();
            }
        }
    }

    private String importAfterSale(CheckoutPort.CheckoutResult order) {
        String no = "r1-as-" + sku;
        jdbc.update(
                "INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,"
                        + "reason,refund_minor,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'REFUND_ONLY','REFUNDING','OTHER',"
                        + "101,NOW(),NOW())",
                no,
                order.subOrderNo(),
                order.orderNo(),
                buyer,
                entity);
        jdbc.update(
                "UPDATE ord_sub_order SET open_after_sale_no=? WHERE sub_order_no=?",
                no,
                order.subOrderNo());
        return no;
    }

    private String importCashExecution(CheckoutPort.CheckoutResult order, String afterSale) {
        String no = "r1-ex-" + sku;
        long item =
                jdbc.queryForObject(
                        "SELECT id FROM ord_item WHERE sub_order_no=?",
                        Long.class,
                        order.subOrderNo());
        int qty = jdbc.queryForObject("SELECT qty FROM ord_item WHERE id=?", Integer.class, item);
        var plan =
                new RefundPlan(
                        order.subOrderNo(), List.of(new RefundLine(item, qty, qty * 101L)), 0);
        String needs = json.writeValueAsString(List.of(new StockPort.SkuQty(sku, qty, store)));
        jdbc.update(
                "INSERT INTO ord_refund_execution(execution_no,after_sale_no,sub_order_no,order_no,"
                        + "amount_minor,currency,plan_json,stock_needs_json,stock_authority,restore_stock,status,phase,"
                        + "next_attempt_at,resource_status,closure_version,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,'CNY',?,?,'PLATFORM',1,'PROCESSING','CASH',0,'PENDING',1,NOW(),NOW())",
                no,
                afterSale,
                order.subOrderNo(),
                order.orderNo(),
                plan.totalMinor(),
                json.writeValueAsString(plan),
                needs);
        return no;
    }

    private void importStockObligation(CheckoutPort.CheckoutResult order, String afterSale)
            throws Exception {
        jdbc.update(
                "INSERT INTO ord_points_refund_execution(after_sale_no,sub_order_no,state,input_hash,"
                        + "payload_json,tenant_no) VALUES (?,?,'COMMITTED',?,'{}','MAIN')",
                afterSale,
                order.subOrderNo(),
                "a".repeat(64));
        int qty =
                jdbc.queryForObject(
                        "SELECT qty FROM ord_item WHERE sub_order_no=?",
                        Integer.class,
                        order.subOrderNo());
        String payload =
                json.writeValueAsString(
                        new Command(
                                afterSale,
                                order.orderNo(),
                                order.subOrderNo(),
                                "PLATFORM",
                                true,
                                true,
                                true,
                                List.of(new StockPort.SkuQty(sku, qty, store))));
        String hash =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(payload.getBytes(StandardCharsets.UTF_8)));
        jdbc.update(
                "INSERT INTO ord_refund_obligation(execution_no,kind,source_no,input_hash,payload_json,state)"
                        + " VALUES (?,'STOCK',?,?,?,'PENDING')",
                "PRF-" + afterSale,
                afterSale,
                hash,
                payload);
    }

    private Map<String, Object> taskCommand(String afterSale) {
        return jdbc.queryForMap(
                "SELECT execution_no,kind,source_no,input_hash,payload_json FROM ord_refund_obligation"
                        + " WHERE execution_no=?",
                "PRF-" + afterSale);
    }

    private Map<String, Object> cashCommand(String no) {
        return jdbc.queryForMap(
                "SELECT execution_no,after_sale_no,order_no,sub_order_no,amount_minor,currency,"
                        + "plan_json,stock_needs_json,stock_authority FROM ord_refund_execution WHERE execution_no=?",
                no);
    }

    private Map<String, Object> originalStock(CheckoutPort.CheckoutResult order) {
        return Map.of(
                "locks",
                jdbc.queryForList(
                        "SELECT * FROM prd_stock_lock WHERE lock_no=? ORDER BY id",
                        order.orderNo()),
                "balances",
                jdbc.queryForList("SELECT stock,locked_stock FROM prd_sku WHERE sku_no=?", sku));
    }

    private void forbidRefundEffects() {
        clearInvocations(payBoundary, resourceStock);
        var violation = new AssertionError("manual original crossed refund recovery authority");
        doThrow(violation).when(payBoundary).queryRefundExecution(any(RefundRequest.class));
        doThrow(violation).when(payBoundary).prepareRefundReversal(any(), anyBoolean());
        doThrow(violation).when(payBoundary).requestRefund(any());
        doThrow(violation).when(resourceStock).releaseAllocated(anyString(), anyString());
        doThrow(violation).when(resourceStock).restoreOriginal(anyString(), anyString(), any());
    }

    private void cashSourceConflict(String executionNo, Runnable action) {
        sourceConflict(action);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT error_code FROM ord_refund_execution WHERE execution_no=?",
                                String.class,
                                executionNo))
                .isEqualTo("REFUND_EXECUTION_CONFLICT");
    }

    private void sourceConflict(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BizException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_PAYMENT_CONFLICT));
    }

    private Map<String, Object> observations(CheckoutPort.CheckoutResult order) {
        return jdbc.queryForMap(
                "SELECT"
                        + " source_revision,source_observation_hash,manual_observation_revision,manual_observation_hash"
                        + " FROM ord_checkout_operation WHERE operation_no=?",
                order.operation().operationNo());
    }
}
