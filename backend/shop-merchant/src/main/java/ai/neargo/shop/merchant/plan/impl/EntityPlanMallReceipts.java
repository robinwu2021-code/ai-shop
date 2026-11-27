package ai.neargo.shop.merchant.plan.impl;

import ai.neargo.commerce.api.port.OfflineCollectionPort.ManualPayment;
import ai.neargo.commerce.api.port.OfflineCollectionPort.ManualResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Immutable matched receipt in the mall database, committed before merchant entitlement delivery. */
final class EntityPlanMallReceipts {
  private final JdbcTemplate mall;
  private final ObjectMapper json;

  EntityPlanMallReceipts(JdbcTemplate mall, ObjectMapper json) {
    this.mall = mall;
    this.json = json;
  }

  ManualResult find(String action, ManualPayment command) {
    var intent = command.original();
    var rows = mall.queryForList("""
        SELECT payment_business_type, order_no, pay_order_no, request_snapshot,
               verified_fact_snapshot->>'$.actionNo' AS action_no FROM mall_payment_ref
        WHERE tenant_no=? AND payment_attempt_no=?
        """, intent.tenantNo(), intent.paymentAttemptNo());
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    if (!"ENTITY_PLAN".equals(row.get("payment_business_type"))
        || !intent.orderNo().equals(row.get("order_no")) || !action.equals(row.get("action_no"))
        || !command.equals(json.readValue(row.get("request_snapshot").toString(), ManualPayment.class)))
      throw new IllegalStateException("ENTITY_PLAN_RECEIPT_CONFLICT");
    return new ManualResult("MATCHED", (String) row.get("pay_order_no"));
  }

  void save(String action, ManualPayment command, ManualResult result) {
    var intent = command.original();
    try {
      mall.update("""
          INSERT INTO mall_payment_ref(tenant_no, created_at, updated_at, version, deleted,
            payment_attempt_no, order_no, pay_order_no, receiver_account_no, payment_business_type,
            currency, amount_minor, status, provider_paid_at, request_snapshot, verified_fact_snapshot)
          VALUES (?,NOW(3),NOW(3),0,0,?,?,?,?,'ENTITY_PLAN',?,?,'SUCCEEDED',?,?,JSON_OBJECT('actionNo',?))
          """, intent.tenantNo(), intent.paymentAttemptNo(), intent.orderNo(), result.paymentNo(),
          command.actualReceiver(), command.actualAmount().currency(), command.actualAmount().minor(),
          command.actualPaidAt().toEpochMilli(), json.writeValueAsString(command), action);
    } catch (DuplicateKeyException duplicate) {
      if (!result.equals(find(action, command))) throw duplicate;
    }
  }
}
