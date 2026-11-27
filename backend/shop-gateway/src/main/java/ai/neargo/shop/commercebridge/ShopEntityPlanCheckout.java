package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade;
import ai.neargo.commerce.api.port.PaymentIntentCommand;
import ai.neargo.shop.merchant.plan.EntityPlanCheckout;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

public final class ShopEntityPlanCheckout implements EntityPlanCheckout {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final ai.neargo.commerce.starter.http.MallHttpContextPort authority;
  private final MallFacade orders;
  private final OfflinePurchaseFacade offline;
  private final PaymentFacade payments;

  public ShopEntityPlanCheckout(JdbcTemplate db, ObjectMapper json, ai.neargo.commerce.starter.http.MallHttpContextPort authority,
      MallFacade orders, OfflinePurchaseFacade offline, PaymentFacade payments) {
    this.db=db;
    this.json=json;
    this.authority=authority;
    this.orders=orders;
    this.offline=offline;
    this.payments=payments;
  }

  private ActorContext actor(boolean assisted, String store) {
    return assisted ? authority.operator("commerce:mall:assisted:purchase")
        : authority.buyer(store,"commerce:mall:purchase").actor();
  }

  public Quote quote(Actor creator, Actor buyer, Offer offer) {
    var store = new StoreRef("MAIN",buyer.storeNo());
    var current = actor(creator.operator(),buyer.storeNo());
    if (!creator.userNo().equals(current.rawIdentity().rawNo())) throw new MallException("FORBIDDEN");
    var quoted = offline.quoteEntityPlan(current,new QuoteCommand(store,
        List.of(new QuoteLine(offer.catalog().skuVersionNo(),1)),offer.price().currency()));
    if (!offer.price().equals(quoted.payable()) || quoted.credit().minor()!=0)
      throw new MallException("ENTITY_PLAN_PRICE_MISMATCH");
    String no=db.queryForObject("""
        SELECT order_no FROM mall_order WHERE tenant_no=? AND entity_no=? AND store_no=? AND quote_no=? AND stage='QUOTE'
        """,String.class,store.tenantNo(),buyer.entityNo(),store.storeNo(),quoted.quoteNo());
    return new Quote(quoted.quoteNo(),no);
  }

  public PaymentIntentCommand issue(Quote quote, String storeNo, boolean assisted, boolean interactive) {
    var stage=db.queryForObject("SELECT stage FROM mall_order WHERE tenant_no='MAIN' AND store_no=? AND quote_no=? AND order_no=?",
        String.class,storeNo,quote.quoteNo(),quote.orderNo());
    if (!"ORDERED".equals(stage)) {
      if (!interactive) throw new IllegalStateException("ENTITY_PLAN_PLACEMENT_PENDING");
      var placed=orders.placeOrder(actor(assisted,storeNo),new PlaceOrderCommand(quote.quoteNo(),
          "entity-plan-"+quote.orderNo(),Optional.empty(),new StoreRef("MAIN",storeNo)));
      if (!quote.orderNo().equals(placed.orderNo())) throw new IllegalStateException("ENTITY_PLAN_ORDER_CONFLICT");
    }
    var existing=db.query("SELECT request_snapshot->'$.payment' FROM mall_payment_ref WHERE tenant_no='MAIN' AND order_no=? AND payment_business_type='PLATFORM_MALL' AND status IN ('PENDING','SUCCEEDED') AND pay_order_no IS NOT NULL",
        (r,n)->json.readValue(r.getString(1),PaymentIntentCommand.class),quote.orderNo());
    if (!existing.isEmpty()) return existing.getFirst();
    var result=payments.prepareOffline(new FulfillmentQuery("MAIN",quote.orderNo()));
    if (result.status()!=AsyncStatus.PENDING) throw new IllegalStateException("PAYMENT_ISSUANCE_UNKNOWN");
    return db.queryForObject("""
        SELECT request_snapshot->'$.payment' FROM mall_payment_ref
        WHERE tenant_no='MAIN' AND order_no=? AND payment_business_type='PLATFORM_MALL'
        """,(r,n)->json.readValue(r.getString(1),PaymentIntentCommand.class),quote.orderNo());
  }

  public boolean discardUnplaced(Quote quote, boolean expiredOnly) {
    db.update("UPDATE mall_order SET stage='QUOTE_EXPIRED',version=version+1,updated_at=UTC_TIMESTAMP(3)"
        +" WHERE tenant_no='MAIN' AND order_no=? AND quote_no=? AND stage='QUOTE' AND idempotency_key IS NULL"
        +(expiredOnly ? " AND quote_expires_at<=UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000" : ""),quote.orderNo(),quote.quoteNo());
    return Boolean.TRUE.equals(db.queryForObject("SELECT stage='QUOTE_EXPIRED' FROM mall_order WHERE tenant_no='MAIN' AND order_no=? AND quote_no=?",
        Boolean.class,quote.orderNo(),quote.quoteNo()));
  }

  public Optional<Receipt> confirmedReceipt(String no,String paymentNo) {
    return db.query("""
        SELECT a.operator_no,a.confirmed_at,a.action_snapshot->'$.review' FROM mall_offline_payment a
        JOIN mall_payment_ref p ON p.tenant_no=a.tenant_no AND p.order_no=a.order_no AND p.pay_order_no=a.payment_no
        JOIN mall_order o ON o.tenant_no=a.tenant_no AND o.order_no=a.order_no
        WHERE a.tenant_no='MAIN' AND a.order_no=? AND a.payment_no=? AND a.record_type='ACTION'
          AND a.kind='CONFIRM' AND a.status='APPLIED' AND p.status='SUCCEEDED' AND p.payment_business_type='PLATFORM_MALL'
          AND o.pricing_policy_version='entity-plan-cash-v1' AND o.pay_status='PAID' AND o.refund_status='NONE'
          AND a.amount_minor=p.amount_minor AND p.amount_minor=o.cash_paid_minor AND a.currency=o.currency
          AND COALESCE(a.action_snapshot->>'$.review.testPayment','false')='false'
        ORDER BY a.confirmed_at,a.action_no LIMIT 1
        """,(r,n)->{
          var review=json.readValue(r.getString(3),OfflinePurchaseFacade.ReviewCommand.class);
          var confirm=new Confirm(review.actionNo(),null,review.actualAmount(),review.actualPaidAt(),
              review.bankReference(),review.evidenceKey(),review.reason());
          return new Receipt(r.getString(1),r.getLong(2),confirm);
        },no,paymentNo).stream().findFirst();
  }

  public void refreshPayment(String no) { payments.refresh(new FulfillmentQuery("MAIN",no)); }

  public List<RefundedPlan> refundedPlans(String after,String orderNo,int limit) {
    String sql="""
        SELECT r.refund_no,r.order_no,o.entity_no,o.store_no FROM mall_refund r
        JOIN mall_order o ON o.tenant_no=r.tenant_no AND o.order_no=r.order_no AND o.store_no=r.store_no
        WHERE r.tenant_no='MAIN' AND r.status='SUCCEEDED' AND o.pricing_policy_version='entity-plan-cash-v1'
          AND r.verified_fact_snapshot IS NOT NULL AND r.command_snapshot->>'$.entityQuotaResult' IS NULL
          AND EXISTS (SELECT 1 FROM mall_refund_item i JOIN mch_subscription_period p
            ON p.tenant_no=i.tenant_no AND p.period_no=i.period_no AND p.store_no=o.store_no
            WHERE i.tenant_no=r.tenant_no AND i.refund_no=r.refund_no AND p.state='REFUNDED')
        """;
    var args=new java.util.ArrayList<Object>();
    if (orderNo!=null) {sql+=" AND r.order_no=?"; args.add(orderNo);}
    args.add(after==null?"":after);
    args.add(limit);
    return db.query(sql+" AND r.refund_no>? ORDER BY r.refund_no LIMIT ?",
        (r,n)->new RefundedPlan(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),args.toArray());
  }

  public boolean reversalDelivered(RefundedPlan refund) {
    var operation=ai.neargo.commerce.core.mall.RefundSnapshot.freezeOperation(refund.refundNo());
    return Boolean.TRUE.equals(db.queryForObject("""
        SELECT NOT EXISTS (SELECT 1 FROM mall_fulfillment_task WHERE tenant_no='MAIN' AND task_type='END_ACTIVATION'
          AND request_snapshot->>'$.store'=? AND request_snapshot->>'$.operation'=? AND status<>'DONE')
        """,Boolean.class,refund.storeNo(),operation));
  }

  public void acknowledgeReversal(RefundedPlan refund,String result) {
    db.update("""
        UPDATE mall_refund SET command_snapshot=JSON_SET(command_snapshot,'$.entityQuotaResult',?),
          version=version+1,updated_at=UTC_TIMESTAMP(3)
        WHERE tenant_no='MAIN' AND refund_no=? AND order_no=? AND status='SUCCEEDED'
          AND command_snapshot->>'$.entityQuotaResult' IS NULL
        """,result,refund.refundNo(),refund.orderNo());
  }

  public boolean fulfill(String no) {
    db.update("""
        UPDATE mall_order SET schedule_preview_snapshot=JSON_SET(schedule_preview_snapshot,'$.entityPlanGranted',CAST('true' AS JSON)),
          version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE tenant_no='MAIN' AND order_no=? AND stage='ORDERED'
          AND pricing_policy_version='entity-plan-cash-v1' AND schedule_preview_snapshot->'$.entityPlanGranted' IS NULL
        """,no);
    var delivery = payments.refresh(new FulfillmentQuery("MAIN",no));
    if (delivery.digitalStatus() != AsyncStatus.SUCCEEDED) return false;
    return Boolean.TRUE.equals(db.queryForObject("""
        SELECT NOT EXISTS(SELECT 1 FROM mall_fulfillment_task WHERE tenant_no='MAIN'
          AND task_type='END_ACTIVATION' AND request_snapshot->>'$.operation'=? AND status<>'DONE')
        """,Boolean.class,no));
  }
}
