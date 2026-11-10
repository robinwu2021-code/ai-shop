package ai.neargo.shop.commercebridge.port;

import ai.neargo.commerce.api.common.AsyncStatus;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.shop.pay.service.*;
import ai.neargo.shop.spi.pay.PlatformPaymentRecoveryPort;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cash acknowledgement and commercial dispatch are separate durable phases. */
public final class ShopMallPaymentRecovery implements PlatformPaymentRecoveryPort {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ShopMallPaymentRecovery.class);
  private final PlatformCollectionService payments;
  private final PlatformRefundService refunds;
  private final PlatformMoneyHandoffService handoffs;
  private final PaymentFacade core;
  private final RefundFacade coreRefunds;
  private final ai.neargo.shop.merchant.plan.EntityPlanPurchaseService entityPlans;

  public ShopMallPaymentRecovery(
      PlatformCollectionService payments,
      PlatformRefundService refunds,
      PlatformMoneyHandoffService handoffs,
      PaymentFacade core,
      RefundFacade coreRefunds) {
    this(payments, refunds, handoffs, core, coreRefunds, null);
  }

  public ShopMallPaymentRecovery(
      PlatformCollectionService payments,
      PlatformRefundService refunds,
      PlatformMoneyHandoffService handoffs,
      PaymentFacade core,
      RefundFacade coreRefunds,
      ai.neargo.shop.merchant.plan.EntityPlanPurchaseService entityPlans) {
    this.entityPlans = entityPlans;
    this.payments = payments;
    this.refunds = refunds;
    this.handoffs = handoffs;
    this.core = core;
    this.coreRefunds = coreRefunds;
  }

  public boolean recover(String id) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Payment handoff must not join a ledger transaction");
    var payment = payments.paymentKey(id);
    if (payment.isPresent()) {
      var receipt = payments.query(payment.get());
      boolean durable = "SUCCESS".equals(receipt.state());
      if (!durable) return false;
      try {
        if (entityPlans != null) {
          var handled =
              entityPlans.recoverPayment(
                  receipt.key().tenantNo(), receipt.key().orderNo(), receipt.paymentNo());
          if (handled.isPresent()) {
            if (handled.get()) handoffs.acknowledge(receipt.paymentNo());
            return true;
          }
        }
        var query = new FulfillmentQuery(receipt.key().tenantNo(), receipt.key().orderNo());
        var result = core.refresh(query);
        if (result == null) return true;
        if (result.digitalStatus() == AsyncStatus.FAILED) {
          if (coreRefunds == null) return true;
          var compensation = coreRefunds.recoverLatePayment(query);
          if (compensation.status() != AsyncStatus.SUCCEEDED) return true;
          core.recover(query); // Resolve original unshipped inventory release fences after
          // late refund.
        }
        handoffs.acknowledge(receipt.paymentNo());
      } catch (RuntimeException retry) {
        log.warn(
            "Mall cash durable; commercial handoff pending payment={} failure={}",
            receipt.paymentNo(),
            retry.getClass().getSimpleName());
      }
      return true;
    }
    if (refunds == null) return false;
    var refund = refunds.refundKey(id);
    if (refund.isEmpty()) return false;
    var receipt = refunds.query(refund.get());
    if (!"SUCCESS".equals(receipt.state())) return false;
    try {
      if (coreRefunds == null) return true;
      var key = receipt.request().key();
      var result =
          coreRefunds.recover(new RefundQuery(key.tenantNo(), key.orderNo(), key.refundNo()));
      if (result.status() == AsyncStatus.SUCCEEDED) {
        core.recover(new FulfillmentQuery(key.tenantNo(), key.orderNo()));
        handoffs.acknowledge(receipt.paymentNo());
      }
    } catch (RuntimeException retry) {
      log.warn(
          "Mall refund durable; commercial handoff pending payment={} failure={}",
          receipt.paymentNo(),
          retry.getClass().getSimpleName());
    }
    return true;
  }
}
