package ai.neargo.shop.merchant.plan;

import ai.neargo.commerce.api.port.PaymentIntentCommand;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.Actor;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.Offer;

/** Uses the mall's own order number, offline payment attempt and digital fulfillment. */
public interface EntityPlanCheckout {
  record Quote(String quoteNo, String orderNo, Boolean mallOffline) {
    public Quote(String quoteNo,String orderNo) { this(quoteNo,orderNo,null); }
  }
  EntityPlanOfflineRecords offlineRecords();
  Quote quote(Actor creator, Actor buyer, Offer offer);
  PaymentIntentCommand issue(Quote quote, String storeNo, boolean assisted, boolean interactive);
  boolean discardUnplaced(Quote quote, boolean expiredOnly);
  record Receipt(String operatorNo,long acceptedAt,EntityPlanPurchaseService.Confirm confirmation) {}
  java.util.Optional<Receipt> confirmedReceipt(String orderNo,String paymentNo);
  void refreshPayment(String orderNo);
  record RefundedPlan(String refundNo,String orderNo,String entityNo,String storeNo) {}
  java.util.List<RefundedPlan> refundedPlans(String afterRefundNo,String orderNo,int limit);
  boolean reversalDelivered(RefundedPlan refund);
  void acknowledgeReversal(RefundedPlan refund,String result);
  boolean fulfill(String orderNo);
}
