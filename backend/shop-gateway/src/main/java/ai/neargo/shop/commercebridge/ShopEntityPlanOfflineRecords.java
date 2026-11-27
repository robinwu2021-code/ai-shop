package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.FulfillmentQuery;
import ai.neargo.commerce.api.mall.OrderQuery;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.offline.OfflineRepository;
import ai.neargo.commerce.persistence.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.plan.EntityPlanOfflineRecords;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

final class ShopEntityPlanOfflineRecords implements EntityPlanOfflineRecords {
  private static final String TENANT = "MAIN";
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final JdbcMallOfflineRepository records;
  private final JdbcCommerceUnitOfWork tx;
  private final OfflinePurchaseFacade workflow;

  ShopEntityPlanOfflineRecords(JdbcTemplate db, ObjectMapper json, OfflinePurchaseFacade workflow) {
    this.db = db;
    this.json = json;
    this.workflow = workflow;
    var ds = Objects.requireNonNull(db.getDataSource());
    records = new JdbcMallOfflineRepository(ds);
    tx = new JdbcCommerceUnitOfWork(ds, new DataSourceTransactionManager(ds));
  }

  private <T> T locked(String order, Supplier<T> work) {
    return tx.required(
        () -> {
          db.queryForObject(
              "SELECT order_no FROM mall_order WHERE tenant_no=? AND order_no=? AND"
                  + " pricing_policy_version='entity-plan-cash-v1' AND stage='ORDERED' FOR UPDATE",
              String.class,
              TENANT,
              order);
          return work.get();
        });
  }

  private static String principal(Actor a) {
    return a.operator() ? a.userNo() : ShopMallIdentity.buyerKey(a.subjectKind(), a.userNo());
  }

  public PrivateEvidencePort.Scope scope(Actor a, String store, String no, String purpose) {
    return new PrivateEvidencePort.Scope(
        new StoreRef(TENANT, store),
        a.entityNo(),
        no,
        a.operator() ? SubjectKind.OPERATOR : SubjectKind.ACCOUNT,
        principal(a),
        purpose);
  }

  public void reserve(PrivateEvidencePort.Scope scope, String key, long at) {
    locked(
        scope.orderNo(),
        () -> {
          records.reserve(new OfflineRepository.Evidence(key, scope, at));
          return null;
        });
  }

  public PrivateEvidencePort.Scope evidence(String order, String key) {
    return db
        .query(
            "SELECT scope_snapshot FROM mall_offline_evidence WHERE tenant_no=? AND order_no=? AND"
                + " asset_key=?",
            (r, i) -> json.readValue(r.getString(1), OfflineRepository.Evidence.class).scope(),
            TENANT,
            order,
            key)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private ClaimView view(OfflineRepository.Claim row) {
    var c = row.command();
    return new ClaimView(
        c.claimNo(),
        row.status(),
        new Claim(
            c.claimNo(),
            c.declaredAmount(),
            c.declaredPaidAt(),
            c.bankReference(),
            c.evidenceKeys().isEmpty() ? null : c.evidenceKeys().getFirst()),
        row.view().reviewNote());
  }

  public List<ClaimView> claims(Actor a, String no) {
    String sql =
        "SELECT action_snapshot FROM mall_offline_payment WHERE tenant_no=? AND order_no=? AND"
            + " record_type='CLAIM'";
    var args = new ArrayList<Object>(List.of(TENANT, no));
    if (!a.operator()) {
      sql += " AND operator_no=?";
      args.add(principal(a));
    }
    return db.query(
        sql + " ORDER BY action_no",
        (r, i) -> view(json.readValue(r.getString(1), OfflineRepository.Claim.class)),
        args.toArray());
  }

  public Map<String, PaymentState> states(List<String> orders) {
    var result = new HashMap<String, PaymentState>();
    if (orders.isEmpty()) return result;
    String sql =
        "SELECT order_no,record_type,status FROM mall_offline_payment WHERE tenant_no='MAIN' AND"
            + " order_no IN ("
            + String.join(",", Collections.nCopies(orders.size(), "?"))
            + ") AND (record_type='CLAIM' OR kind='CONFIRM')"
            + " ORDER BY (status='SUBMITTED') DESC, created_at DESC,action_no DESC";
    db.query(
        sql,
        (org.springframework.jdbc.core.RowCallbackHandler)
            r -> {
              var no = r.getString(1);
              var prev = result.getOrDefault(no, new PaymentState(null, null));
              if ("CLAIM".equals(r.getString(2)) && prev.claim() == null)
                result.put(no, new PaymentState(r.getString(3), prev.action()));
              if ("ACTION".equals(r.getString(2)) && prev.action() == null)
                result.put(no, new PaymentState(prev.claim(), r.getString(3)));
            },
        orders.toArray());
    return result;
  }

  public List<ActionView> actions(String no) {
    return db.query(
        "SELECT action_no,status,payment_no,COALESCE(failure_code,review_note) FROM"
            + " mall_offline_payment WHERE tenant_no=? AND order_no=? AND record_type='ACTION' AND"
            + " kind='CONFIRM' ORDER BY created_at,action_no",
        (r, i) -> new ActionView(r.getString(1), r.getString(2), r.getString(3), r.getString(4)),
        TENANT,
        no);
  }

  public ClaimView submit(Actor a, String store, String no, Claim c, long at, boolean open) {
    var cmd =
        new OfflinePurchaseFacade.ClaimCommand(
            new OrderQuery(new StoreRef(TENANT, store), no),
            c.claimNo(),
            c.amount(),
            c.paidAt(),
            c.bankReference(),
            List.of(c.evidenceKey()));
    return locked(
        no,
        () -> {
          var prior = records.claim(TENANT, no, c.claimNo());
          if (prior.isPresent()) {
            var row = prior.get();
            if (!row.command().equals(cmd) || !principal(a).equals(row.submitterNo()))
              throw BizException.of(ErrorCode.BAD_REQUEST);
            return view(row);
          }
          if (!open
              || records.claims(TENANT, no).stream()
                  .anyMatch(r -> Set.of("SUBMITTED", "REVIEWING").contains(r.status()))
              || activeConfirmation(no)) throw BizException.of(ErrorCode.BAD_REQUEST);
          var row = new OfflineRepository.Claim(cmd, principal(a), at, "SUBMITTED", null, null);
          records.saveClaim(row);
          return view(row);
        });
  }

  public ClaimView decline(Actor a, String no, String claim, String reason) {
    return locked(
        no,
        () -> {
          var row =
              records
                  .claim(TENANT, no, claim)
                  .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
          if ("DECLINED".equals(row.status())
              && reason.equals(row.reason())
              && principal(a).equals(row.reviewer())) return view(row);
          if (!"SUBMITTED".equals(row.status())) throw BizException.of(ErrorCode.BAD_REQUEST);
          var declined = row.reviewed("DECLINED", principal(a), reason);
          records.saveClaim(declined);
          return view(declined);
        });
  }

  private boolean activeConfirmation(String order) {
    return records.actions(TENANT, order).stream()
        .anyMatch(row -> "CONFIRM".equals(row.kind()) && !"REJECTED".equals(row.status()));
  }

  public void accept(
      Actor a, String store, PaymentIntentCommand payment, Confirm c, long at, boolean open) {
    var no = payment.orderNo();
    var target = new OrderQuery(new StoreRef(TENANT, store), no);
    var review =
        new OfflinePurchaseFacade.ReviewCommand(
            target,
            c.actionNo(),
            c.claimNo(),
            c.amount(),
            c.paidAt(),
            payment.platformMerchantNo(),
            c.bankReference(),
            c.evidenceKey(),
            false,
            c.reason());
    locked(
        no,
        () -> {
          var existing = records.action(TENANT, no, c.actionNo());
          if (existing.isPresent()) {
            if (!review.equals(existing.get().review())
                || !principal(a).equals(existing.get().operatorNo()))
              throw BizException.of(ErrorCode.BAD_REQUEST);
            return null;
          }
          if (!open || activeConfirmation(no)) throw BizException.of(ErrorCode.BAD_REQUEST);
          if (c.claimNo() == null
              && records.claims(TENANT, no).stream()
                  .anyMatch(row -> "SUBMITTED".equals(row.status())))
            throw BizException.of(ErrorCode.BAD_REQUEST);
          if (c.claimNo() != null) {
            var claim =
                records
                    .claim(TENANT, no, c.claimNo())
                    .filter(r -> "SUBMITTED".equals(r.status()))
                    .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
            records.saveClaim(claim.reviewed("REVIEWING", principal(a), c.reason()));
          }
          records.saveAction(
              new OfflineRepository.Action(
                  c.actionNo(),
                  "CONFIRM",
                  target,
                  principal(a),
                  at,
                  review,
                  null,
                  payment,
                  "ACCEPTED",
                  null));
          return null;
        });
  }

  public void resume(String no) {
    workflow.recover(new FulfillmentQuery(TENANT, no));
  }
}
