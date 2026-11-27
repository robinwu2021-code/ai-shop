package ai.neargo.shop.merchant.plan.impl;

import ai.neargo.commerce.api.port.OfflineCollectionPort.ManualPayment;
import ai.neargo.commerce.api.port.OfflineCollectionPort.ManualResult;
import ai.neargo.commerce.api.port.PaymentIntentCommand;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** The mall owns the frozen payment attempt and its verified receipt. */
final class EntityPlanMallReceipts {
  private final JdbcTemplate mall;
  private final ObjectMapper json;

  EntityPlanMallReceipts(JdbcTemplate mall, ObjectMapper json) {
    this.mall = mall;
    this.json = json;
  }

  private Map<String, Object> attempt(PaymentIntentCommand intent) {
    var rows = mall.queryForList("""
        SELECT payment_business_type, order_no, pay_order_no, status, closure_reference,
          COALESCE(JSON_EXTRACT(request_snapshot,'$.original'),request_snapshot) AS intent,
          COALESCE(JSON_EXTRACT(verified_fact_snapshot,'$.command'),request_snapshot) AS confirmation,
          verified_fact_snapshot->>'$.actionNo' AS action_no FROM mall_payment_ref
        WHERE tenant_no=? AND payment_attempt_no=?
        """, intent.tenantNo(), intent.paymentAttemptNo());
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    // Receipt-only rows written before issuance moved to the mall store the intent under original.
    if (!"ENTITY_PLAN".equals(row.get("payment_business_type"))
        || !intent.orderNo().equals(row.get("order_no"))
        || !intent.equals(json.readValue(row.get("intent").toString(), PaymentIntentCommand.class)))
      throw new IllegalStateException("ENTITY_PLAN_PAYMENT_INTENT_CONFLICT");
    return row;
  }

  void prepare(PaymentIntentCommand intent) {
    if (attempt(intent) != null) return;
    try {
      mall.update("""
          INSERT INTO mall_payment_ref(tenant_no,created_at,updated_at,version,deleted,payment_attempt_no,
            order_no,receiver_account_no,payment_business_type,currency,amount_minor,status,request_snapshot)
          VALUES (?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0,?,?,?,'ENTITY_PLAN',?,?,'CREATED',?)
          """, intent.tenantNo(), intent.paymentAttemptNo(), intent.orderNo(), intent.platformMerchantNo(),
          intent.amount().currency(), intent.amount().minor(), json.writeValueAsString(intent));
    } catch (DuplicateKeyException concurrent) {
      if (attempt(intent) == null) throw concurrent;
    }
  }

  ManualResult find(String action, ManualPayment command) {
    var row = attempt(command.original());
    if (row == null || !"SUCCEEDED".equals(row.get("status")) || !action.equals(row.get("action_no"))) return null;
    if (!command.equals(json.readValue(row.get("confirmation").toString(), ManualPayment.class)))
      throw new IllegalStateException("ENTITY_PLAN_RECEIPT_CONFLICT");
    return new ManualResult("MATCHED", (String) row.get("pay_order_no"));
  }

  void save(String action, ManualPayment command, ManualResult result) {
    var intent = command.original();
    prepare(intent); // An order issued before cutover can reach confirmation without a mall row.
    int updated = mall.update("""
        UPDATE mall_payment_ref SET status='SUCCEEDED',pay_order_no=?,provider_paid_at=?,
          verified_fact_snapshot=?,updated_at=UTC_TIMESTAMP(3),version=version+1
        WHERE tenant_no=? AND payment_attempt_no=? AND status='CREATED' AND payment_business_type='ENTITY_PLAN'
        """, result.paymentNo(), command.actualPaidAt().toEpochMilli(),
        json.writeValueAsString(Map.of("actionNo", action, "command", command)),
        intent.tenantNo(), intent.paymentAttemptNo());
    if (updated == 0 && !result.equals(find(action, command)))
      throw new IllegalStateException("ENTITY_PLAN_RECEIPT_CONFLICT");
  }

  void close(PaymentIntentCommand intent, String reference) {
    int changed = mall.update("""
        UPDATE mall_payment_ref SET status=IF(status='CREATED','CLOSED',status),
          closure_reference=?,updated_at=UTC_TIMESTAMP(3),version=version+1
        WHERE tenant_no=? AND payment_attempt_no=? AND payment_business_type='ENTITY_PLAN' AND closure_reference IS NULL
        """, reference, intent.tenantNo(), intent.paymentAttemptNo());
    if (changed == 0) {
      var row = attempt(intent);
      if (row == null || !reference.equals(row.get("closure_reference")))
        throw new IllegalStateException("ENTITY_PLAN_CLOSURE_CONFLICT");
    }
  }
}
