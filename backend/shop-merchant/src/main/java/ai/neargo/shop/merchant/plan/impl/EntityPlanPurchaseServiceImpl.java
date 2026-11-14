package ai.neargo.shop.merchant.plan.impl;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.offline.OfflineCollectionTerms;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.plan.*;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Frozen entity purchases; all ledger/media calls run outside the business transaction. */
public final class EntityPlanPurchaseServiceImpl implements EntityPlanPurchaseService {
  private static final String TENANT = "MAIN";
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final PlatformPaymentPort payments;
  private final OfflineCollectionPort money;
  private final PrivateEvidencePort media;
  private final MoneyPolicyPort policies;
  private final StoreOwnershipWritePort ownership;
  private final Predicate<String> opsVisible;
  private final Clock clock;
  private final String profileNo;

  public record Snapshot(
      String entityNo,
      String storeNo,
      String buyerNo,
      String buyerKind,
      String ownerAccountNo,
      Offer offer,
      String previousPlan,
      Long previousExpiry,
      String previousGrant,
      PaymentIntentCommand intent,
      String creatorNo,
      String creatorKind) {}

  public EntityPlanPurchaseServiceImpl(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      ObjectMapper json,
      PlatformPaymentPort payments,
      OfflineCollectionPort money,
      PrivateEvidencePort media,
      MoneyPolicyPort policies,
      StoreOwnershipWritePort ownership,
      Predicate<String> opsVisible,
      Clock clock,
      String profileNo) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.json = json;
    this.payments = payments;
    this.money = money;
    this.media = media;
    this.policies = policies;
    this.ownership = ownership;
    this.opsVisible = opsVisible;
    this.clock = clock;
    this.profileNo = profileNo;
  }

  private static RuntimeException bad() {
    return BizException.of(ErrorCode.BAD_REQUEST);
  }

  private static RuntimeException denied() {
    return BizException.of(ErrorCode.FORBIDDEN);
  }

  private String encode(Object value) {
    return json.writeValueAsString(value);
  }

  private <T> T decode(Object value, Class<T> type) {
    return json.readValue(value.toString(), type);
  }

  private <T> T write(Supplier<T> work) {
    return tx.execute(s -> work.get());
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private static String str(Map<String, Object> row, String key) {
    return row == null || row.get(key) == null ? null : row.get(key).toString();
  }

  private static Long number(Map<String, Object> row, String key) {
    return row == null || row.get(key) == null ? null : ((Number) row.get(key)).longValue();
  }

  private static boolean bool(Map<String, Object> row, String key) {
    return Boolean.TRUE.equals(row.get(key)) || "1".equals(str(row, key));
  }

  private Map<String, Object> authorize(Actor a) {
    if (a == null || a.userNo() == null || a.entityNo() == null) throw denied();
    if (a.operator() && !opsVisible.test(a.entityNo())) throw denied();
    var e =
        one(
            "SELECT * FROM mch_entity WHERE tenant_no=? AND entity_no=?"+(a.operator()?"":" AND deleted=0"),
            TENANT,
            a.entityNo());
    if (e == null || !a.operator() && ownerMembership(a, false) == null) throw denied();
    return e;
  }

  private Map<String, Object> ownerMembership(Actor a, boolean lock) {
    String column =
        switch (a.subjectKind()) {
          case "USR" -> "user_no";
          case "MCH" -> "mch_account_no";
          default -> throw denied();
        };
    return one(
        "SELECT mch_account_no FROM mch_account WHERE tenant_no=? AND entity_no=? AND "
            + column
            + "=? AND is_owner=1 AND status='ACTIVE' AND deleted=0 ORDER BY mch_account_no LIMIT 1"
            + (lock ? " FOR UPDATE" : ""),
        TENANT,
        a.entityNo(),
        a.userNo());
  }

  private void operator(Actor a) {
    authorize(a);
    if (!a.operator()) throw denied();
  }

  private Map<String, Object> row(Actor a, String no, boolean lock) {
    authorize(a);
    var r =
        one(
            "SELECT * FROM mch_plan_purchase WHERE tenant_no=? AND order_no=? AND entity_no=?"
                + (lock ? " FOR UPDATE" : ""),
            TENANT,
            no,
            a.entityNo());
    if (r == null) throw BizException.of(ErrorCode.NOT_FOUND);
    if (!a.operator()
        && (!a.userNo().equals(str(r, "buyer_no"))
            || !a.subjectKind().equals(str(r, "buyer_kind")))) throw denied();
    return r;
  }

  private Snapshot snapshot(Map<String, Object> row) {
    return decode(row.get("snapshot_json"), Snapshot.class);
  }

  private static String currency(Map<String, Object> entity) {
    return switch (Objects.toString(entity.get("market"), "")) {
      case "TW" -> "TWD";
      case "CN" -> "CNY";
      default -> "";
    };
  }

  private void validMoney(Money value, boolean positive) {
    if (value == null
        || positive && value.minor() <= 0
        || !policies.policy(TENANT, value.currency()).map(p -> p.onGrid(value)).orElse(false))
      throw bad();
  }

  private void validTime(Instant at) {
    if (at == null || at.getNano() != 0 || at.isAfter(clock.instant())) throw bad();
  }

  @Override
  public List<Offer> offers(Actor a) {
    var entity = authorize(a);
    String currency = currency(entity);
    var plan =
        one(
            "SELECT plan_code,expire_at,granted_by FROM mch_entity_plan WHERE tenant_no=? AND"
                + " entity_no=? AND deleted=0",
            TENANT,
            a.entityNo());
    return db
        .queryForList(
            "SELECT snapshot_json FROM mch_plan_offer WHERE tenant_no=? ORDER BY offer_no", TENANT)
        .stream()
        .map(r -> decode(r.get("snapshot_json"), Offer.class))
        .filter(
            o ->
                a.operator()
                    || o.enabled()
                        && currency.equals(o.price().currency())
                        && one(
                                "SELECT plan_code FROM sys_merchant_plan_def WHERE tenant_no=? AND"
                                    + " plan_code=? AND enabled=1 AND deleted=0",
                                TENANT,
                                o.planCode())
                            != null
                        && money.terms(TENANT, profileNo, currency).isPresent()
                        && EntityPlanPurchasePolicy.canBuy(
                            plan == null ? "FREE" : str(plan, "plan_code"),
                            number(plan, "expire_at"),
                            plan == null ? "SELF_PAID" : str(plan, "granted_by"),
                            o.planCode(),
                            clock.millis()))
        .toList();
  }

  @Override
  public Offer saveOffer(Actor a, OfferEdit c) {
    operator(a);
    validMoney(c.price(), true);
    if (c.days() < 1
        || c.days() > 3660
        || c.name() == null
        || c.name().isBlank()
        || !Set.of("PRO", "CHAIN").contains(c.planCode())
        || c.offerNo() == null
        || !c.offerNo().matches("[A-Za-z0-9_-]{1,64}")) throw bad();
    return write(
        () -> {
          var d =
              one(
                  "SELECT * FROM sys_merchant_plan_def WHERE tenant_no=? AND plan_code=? AND"
                      + " deleted=0 FOR UPDATE",
                  TENANT,
                  c.planCode());
          if (d == null || c.enabled() && !bool(d, "enabled")) throw bad();
          var prior =
              one(
                  "SELECT * FROM mch_plan_offer WHERE tenant_no=? AND offer_no=? FOR UPDATE",
                  TENANT,
                  c.offerNo());
          long version = prior == null ? 0 : number(prior, "version");
          if (version != c.expectedVersion()) throw bad();
          var offer =
              new Offer(
                  c.offerNo(),
                  version + 1,
                  c.planCode(),
                  c.name(),
                  c.days(),
                  c.price(),
                  number(d, "store_quota").intValue(),
                  number(d, "staff_quota").intValue(),
                  bool(d, "cross_store_stats"),
                  c.enabled());
          if (prior == null)
            db.update(
                "INSERT INTO mch_plan_offer VALUES(?,?,?,?)",
                TENANT,
                c.offerNo(),
                offer.version(),
                encode(offer));
          else
            db.update(
                "UPDATE mch_plan_offer SET version=?,snapshot_json=? WHERE tenant_no=? AND"
                    + " offer_no=?",
                offer.version(),
                encode(offer),
                TENANT,
                c.offerNo());
          return offer;
        });
  }

  @Override
  public List<OrderView> orders(Actor a) {
    authorize(a);
    return db
        .queryForList(
            "SELECT order_no FROM mch_plan_purchase WHERE tenant_no=? AND entity_no=?"
                + (a.operator() ? "" : " AND buyer_no=? AND buyer_kind=?")
                + " ORDER BY created_at DESC,order_no DESC LIMIT 100",
            a.operator()
                ? new Object[] {TENANT, a.entityNo()}
                : new Object[] {TENANT, a.entityNo(), a.userNo(), a.subjectKind()})
        .stream()
        .map(r -> order(a, str(r, "order_no")))
        .toList();
  }

  @Override
  public List<OrderView> operatorOrders(Actor a, String afterOrderNo, int limit) {
    operator(a);
    if (limit < 1 || limit > 100) throw bad();
    return db.queryForList(
            "SELECT order_no FROM mch_plan_purchase WHERE tenant_no=? AND entity_no=?"
                + " AND order_no>? ORDER BY order_no LIMIT ?",
            TENANT, a.entityNo(), afterOrderNo == null ? "" : afterOrderNo, limit)
        .stream().map(row -> order(a, str(row, "order_no"))).toList();
  }

  private static final String REPORT_SELECT = "SELECT p.*,"
      + "(SELECT c.status FROM mch_plan_purchase_claim c WHERE c.tenant_no=p.tenant_no AND c.order_no=p.order_no"
      + " ORDER BY CASE WHEN c.status='SUBMITTED' THEN 0 ELSE 1 END,c.claim_no DESC LIMIT 1) claim_status"
      + " FROM mch_plan_purchase p WHERE p.tenant_no=?";

  private void reportingOperator(Actor a, String tenant) {
    if (a == null || !a.operator() || a.userNo() == null || a.userNo().isBlank()
        || !"OPERATOR".equals(a.subjectKind()) || !TENANT.equals(tenant)) throw denied();
  }

  @Override
  public List<Row> searchOperatorOrders(Actor a, Query q) {
    reportingOperator(a, q.access().tenantNo());
    var access = q.access(); var f = q.filter();
    if (!access.allEntities() && access.entityNos().isEmpty()
        || f.entityNo() != null && !access.allows(f.entityNo())) return List.of();
    var sql = new StringBuilder(REPORT_SELECT); var args = new ArrayList<Object>(); args.add(TENANT);
    if (!access.allEntities()) {
      sql.append(" AND p.entity_no IN (").append(reportMarks(access.entityNos().size())).append(')');
      args.addAll(access.entityNos());
    }
    reportEqual(sql,args,"p.entity_no",f.entityNo());
    reportEqual(sql,args,"JSON_UNQUOTE(JSON_EXTRACT(p.snapshot_json,'$.storeNo'))",f.storeNo());
    reportEqual(sql,args,"p.order_no",f.orderNo()); reportEqual(sql,args,"p.status",f.status());
    reportEqual(sql,args,"JSON_UNQUOTE(JSON_EXTRACT(p.snapshot_json,'$.offer.price.currency'))",f.currency());
    if (f.createdFrom()!=null) { sql.append(" AND p.created_at>=?"); args.add(f.createdFrom()); }
    if (f.createdTo()!=null) { sql.append(" AND p.created_at<?"); args.add(f.createdTo()); }
    if (f.pendingOnly()) sql.append(" AND EXISTS (SELECT 1 FROM mch_plan_purchase_claim c"
        + " WHERE c.tenant_no=p.tenant_no AND c.order_no=p.order_no AND c.status='SUBMITTED')");
    if (q.after()!=null) {
      var c=q.after(); sql.append(" AND (p.created_at<? OR (p.created_at=? AND ");
      args.add(c.createdAt()); args.add(c.createdAt());
      if(c.model()==Model.STORE) sql.append("1=0");
      else { sql.append("p.order_no<?"); args.add(c.orderNo()); }
      sql.append("))");
    }
    sql.append(" ORDER BY p.created_at DESC,p.order_no DESC LIMIT ?"); args.add(q.limit());
    return db.queryForList(sql.toString(),args.toArray()).stream().map(this::reportRow).toList();
  }

  @Override
  public List<Row> findOperatorOrderOwners(Actor a, String tenant, List<String> nos) {
    reportingOperator(a, tenant);
    if (nos==null || nos.size()>500) throw bad();
    if (nos.isEmpty()) return List.of();
    var args=new ArrayList<Object>(); args.add(tenant); args.addAll(nos);
    return db.queryForList(REPORT_SELECT+" AND p.order_no IN ("+reportMarks(nos.size())
        +") ORDER BY p.created_at DESC,p.order_no DESC",args.toArray()).stream().map(this::reportRow).toList();
  }

  private Row reportRow(Map<String,Object> r) {
    var snap=json.readTree(str(r,"snapshot_json")); var price=snap.path("offer").path("price");
    var store=snap.path("storeNo");
    return new Row(Model.ENTITY,str(r,"order_no"),str(r,"entity_no"),store.isNull() || store.isMissingNode()?null:store.asText(),
        price.path("currency").asText(),price.path("minor").asText(),str(r,"status"),str(r,"claim_status"),number(r,"created_at"));
  }

  private static String reportMarks(int n) { return String.join(",",Collections.nCopies(n,"?")); }
  private static void reportEqual(StringBuilder sql,List<Object> args,String column,String value) {
    if(value!=null) { sql.append(" AND ").append(column).append("=?"); args.add(value); }
  }

  @Override
  public OrderView buy(Actor a, Buy c) {
    if (a.operator()) throw denied();
    return createPurchase(a, a, c, c, null);
  }

  @Override
  public AssistanceContext assistanceContext(Actor a) {
    operator(a);
    var owners =
        db
            .queryForList(
                "SELECT mch_account_no,display_name,user_no FROM mch_account WHERE tenant_no=? AND"
                    + " entity_no=? AND is_owner=1 AND status='ACTIVE' AND deleted=0 ORDER BY"
                    + " mch_account_no",
                TENANT,
                a.entityNo())
            .stream()
            .map(
                r ->
                    new AssistanceOwner(
                        str(r, "mch_account_no"),
                        str(r, "display_name"),
                        str(r, "user_no") == null || str(r, "user_no").isBlank() ? "MCH" : "USR"))
            .toList();
    var stores =
        db
            .queryForList(
                "SELECT store_no,name FROM mch_store WHERE tenant_no=? AND entity_no=? AND"
                    + " status='ACTIVE' AND deleted=0 ORDER BY store_no",
                TENANT,
                a.entityNo())
            .stream()
            .map(r -> new AssistanceStore(str(r, "store_no"), str(r, "name")))
            .toList();
    return new AssistanceContext(owners, stores);
  }

  @Override
  public OrderView assistedBuy(Actor creator, AssistedBuy c) {
    operator(creator);
    if (one(
            "SELECT store_no FROM mch_store WHERE tenant_no=? AND entity_no=? AND store_no=?"
                + " AND status='ACTIVE' AND deleted=0",
            TENANT,
            creator.entityNo(),
            c.storeNo())
        == null) throw denied();
    var owner =
        one(
            "SELECT mch_account_no,user_no FROM mch_account WHERE tenant_no=? AND entity_no=?"
                + " AND mch_account_no=? AND is_owner=1 AND status='ACTIVE' AND deleted=0",
            TENANT,
            creator.entityNo(),
            c.ownerAccountNo());
    if (owner == null) throw denied();
    String user = str(owner, "user_no");
    boolean linked = user != null && !user.isBlank();
    var buyer =
        new Actor(
            linked ? user : c.ownerAccountNo(),
            creator.entityNo(),
            c.storeNo(),
            false,
            linked ? "USR" : "MCH");
    return createPurchase(
        creator,
        buyer,
        new Buy(c.requestNo(), c.offerNo(), c.offerVersion()),
        c,
        c.ownerAccountNo());
  }

  private String creatorKind(Actor creator) {
    return creator.operator() ? "OPS" : creator.subjectKind();
  }

  private void checkReplay(
      Map<String, Object> r, Actor buyer, Object request, String ownerAccount) {
    var frozen = snapshot(r);
    if (!json.readTree(r.get("request_json").toString()).equals(json.readTree(encode(request)))
        || !buyer.entityNo().equals(frozen.entityNo())
        || !Objects.equals(buyer.storeNo(), frozen.storeNo())
        || !buyer.userNo().equals(frozen.buyerNo())
        || !buyer.subjectKind().equals(frozen.buyerKind())
        || ownerAccount != null && !ownerAccount.equals(frozen.ownerAccountNo())) throw bad();
  }

  private OrderView createPurchase(
      Actor creator, Actor a, Buy c, Object request, String ownerAccount) {
    var entity = authorize(a);
    if (c == null || c.requestNo() == null || !c.requestNo().matches("[A-Za-z0-9_-]{1,64}"))
      throw bad();
    // Resolve the trusted version before the write, but never disclose instructions until issuance.
    Offer offer =
        offers(a).stream()
            .filter(
                o ->
                    o.offerNo().equals(c.offerNo())
                        && o.version() == c.offerVersion()
                        && o.enabled())
            .findFirst()
            .orElse(null);
    var replay =
        one(
            "SELECT * FROM mch_plan_purchase WHERE tenant_no=? AND creator_no=?"
                + " AND creator_kind=? AND request_no=?",
            TENANT,
            creator.userNo(),
            creatorKind(creator),
            c.requestNo());
    if (replay != null) {
      checkReplay(replay, a, request, ownerAccount);
      return issue(creator, str(replay, "order_no"));
    }
    if (offer == null || !currency(entity).equals(offer.price().currency())) throw bad();
    validMoney(offer.price(), true);
    OfflineCollectionTerms terms =
        money
            .terms(TENANT, profileNo, offer.price().currency())
            .orElseThrow(EntityPlanPurchaseServiceImpl::bad);
    String order =
        write(
            () -> {
              ownership.lockEntityAndCheck(TENANT, a.entityNo());
              authorize(creator);
              authorize(a);
              var owner =
                  ownerAccount == null
                      ? ownerMembership(a, true)
                      : one(
                          "SELECT mch_account_no,user_no FROM mch_account WHERE tenant_no=? AND"
                              + " entity_no=? AND mch_account_no=? AND is_owner=1 AND"
                              + " status='ACTIVE' AND deleted=0 FOR UPDATE",
                          TENANT,
                          a.entityNo(),
                          ownerAccount);
              if (owner == null
                  || ownerAccount != null
                      && !("USR".equals(a.subjectKind())
                          ? a.userNo().equals(str(owner, "user_no"))
                          : (str(owner, "user_no") == null || str(owner, "user_no").isBlank())
                              && a.userNo().equals(str(owner, "mch_account_no")))) throw denied();
              var old =
                  one(
                      "SELECT * FROM mch_plan_purchase WHERE tenant_no=? AND"
                          + " creator_no=? AND creator_kind=? AND request_no=? FOR UPDATE",
                      TENANT,
                      creator.userNo(),
                      creatorKind(creator),
                      c.requestNo());
              if (old != null) {
                checkReplay(old, a, request, ownerAccount);
                return str(old, "order_no");
              }
              var currentOffer =
                  one(
                      "SELECT snapshot_json FROM mch_plan_offer WHERE tenant_no=? AND offer_no=?"
                          + " FOR UPDATE",
                      TENANT,
                      c.offerNo());
              if (currentOffer == null
                  || !decode(currentOffer.get("snapshot_json"), Offer.class).equals(offer))
                throw bad();
              if (one(
                      "SELECT plan_code FROM sys_merchant_plan_def WHERE tenant_no=? AND"
                          + " plan_code=? AND enabled=1 AND deleted=0",
                      TENANT,
                      offer.planCode())
                  == null) throw bad();
              ownership.lockAndCheck(TENANT, a.entityNo(), a.storeNo());
              if (one(
                      "SELECT store_no FROM mch_store WHERE tenant_no=? AND entity_no=? AND"
                          + " store_no=? AND deleted=0",
                      TENANT,
                      a.entityNo(),
                      a.storeNo())
                  == null) throw denied();
              if (creator.operator()
                  && one(
                          "SELECT store_no FROM mch_store WHERE tenant_no=? AND entity_no=? AND"
                              + " store_no=? AND status='ACTIVE' AND deleted=0",
                          TENANT,
                          a.entityNo(),
                          a.storeNo())
                      == null) throw denied();
              var plan =
                  one(
                      "SELECT * FROM mch_entity_plan WHERE tenant_no=? AND entity_no=? AND"
                          + " deleted=0 FOR UPDATE",
                      TENANT,
                      a.entityNo());
              String prior = plan == null ? "FREE" : str(plan, "plan_code"),
                  grant = plan == null ? "SELF_PAID" : str(plan, "granted_by");
              Long expiry = number(plan, "expire_at");
              long now = clock.millis();
              if (!EntityPlanPurchasePolicy.canBuy(prior, expiry, grant, offer.planCode(), now))
                throw bad();
              if (one(
                      "SELECT order_no FROM mch_plan_purchase WHERE tenant_no=? AND open_entity=?",
                      TENANT,
                      a.entityNo())
                  != null) throw bad();
              String no = "EQ" + UUID.randomUUID().toString().replace("-", "");
              Instant prepared = Instant.ofEpochMilli(now),
                  due = prepared.plusMillis(terms.paymentWindowMs());
              var intent =
                  new PaymentIntentCommand(
                      TENANT,
                      no,
                      terms.platformMerchantNo(),
                      offer.price(),
                      due,
                      no,
                      a.userNo(),
                      "OFFLINE",
                      "BANK_TRANSFER",
                      null,
                      prepared,
                      null,
                      terms);
              var frozen =
                  new Snapshot(
                      a.entityNo(),
                      a.storeNo(),
                      a.userNo(),
                      a.subjectKind(),
                      str(owner, "mch_account_no"),
                      offer,
                      prior,
                      expiry,
                      grant,
                      intent,
                      creator.userNo(),
                      creatorKind(creator));
              db.update(
                  "INSERT INTO"
                      + " mch_plan_purchase(tenant_no,order_no,entity_no,buyer_no,buyer_kind,creator_no,creator_kind,request_no,request_json,snapshot_json,status,open_entity,created_at)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?,'PREPARED',?,?)",
                  TENANT,
                  no,
                  a.entityNo(),
                  a.userNo(),
                  a.subjectKind(),
                  creator.userNo(),
                  creatorKind(creator),
                  c.requestNo(),
                  encode(request),
                  encode(frozen),
                  a.entityNo(),
                  now);
              return no;
            });
    return issue(creator, order);
  }

  private OrderView issue(Actor a, String no) {
    var row = row(a, no, false);
    if (!"PREPARED".equals(str(row, "status"))) return order(a, no);
    var intent = snapshot(row).intent();
    var result = payments.create(intent);
    if (result == null
        || result.status() != AsyncStatus.PENDING
        || !intent.orderNo().equals(result.orderNo())
        || !intent.amount().equals(result.amount())
        || !intent.platformMerchantNo().equals(result.platformMerchantNo()))
      throw new IllegalStateException("PAYMENT_ISSUANCE_UNKNOWN");
    write(
        () ->
            db.update(
                "UPDATE mch_plan_purchase SET status='AWAITING_PAYMENT' WHERE tenant_no=? AND"
                    + " order_no=? AND status='PREPARED'",
                TENANT,
                no));
    return order(a, no);
  }

  @Override
  public OrderView order(Actor a, String no) {
    var r = row(a, no, false);
    var s = snapshot(r);
    var claims =
        db
            .queryForList(
                "SELECT * FROM mch_plan_purchase_claim WHERE tenant_no=? AND order_no=? ORDER BY"
                    + " claim_no",
                TENANT,
                no)
            .stream()
            .map(this::claimView)
            .toList();
    var actions =
        db
            .queryForList(
                "SELECT * FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? ORDER BY"
                    + " confirmed_at,action_no",
                TENANT,
                no)
            .stream()
            .map(this::actionView)
            .toList();
    return new OrderView(
        no,
        str(r, "request_no"),
        s.entityNo(),
        s.storeNo(),
        s.offer(),
        str(r, "status"),
        s.intent().expiresAt(),
        "PREPARED".equals(str(r, "status")) ? null : s.intent().offline(),
        claims,
        actions,"OPS".equals(s.creatorKind())?"OPERATOR":"MERCHANT");
  }

  private PrivateEvidencePort.Scope scope(Actor a, Snapshot s, String purpose) {
    return new PrivateEvidencePort.Scope(
        new StoreRef(TENANT, s.storeNo()),
        s.entityNo(),
        s.intent().orderNo(),
        a.operator() ? SubjectKind.OPERATOR : SubjectKind.ACCOUNT,
        a.userNo(),
        purpose);
  }

  private String purpose(Actor a) {
    return a.operator() ? "PLATFORM_RECEIPT" : "BUYER_RECEIPT";
  }

  private PrivateEvidencePort.Scope reserved(Actor a, String no, String key, boolean own) {
    var s = snapshot(row(a, no, false));
    var r =
        one(
            "SELECT * FROM mch_plan_purchase_evidence WHERE tenant_no=? AND order_no=? AND"
                + " asset_key=?",
            TENANT,
            no,
            key);
    if (r == null
        || own
            && (!a.userNo().equals(str(r, "submitter_no"))
                || !a.subjectKind().equals(str(r, "subject_kind"))
                || !(a.operator()
                    ? Set.of("PLATFORM_RECEIPT", "REFUND_PROOF", "RECONCILIATION")
                        .contains(str(r, "purpose"))
                    : "BUYER_RECEIPT".equals(str(r, "purpose"))))) throw denied();
    return new PrivateEvidencePort.Scope(
        new StoreRef(TENANT, s.storeNo()),
        s.entityNo(),
        no,
        "PLATFORM_RECEIPT".equals(str(r, "purpose")) ? SubjectKind.OPERATOR : SubjectKind.ACCOUNT,
        str(r, "submitter_no"),
        str(r, "purpose"));
  }

  @Override
  public String reserveEvidence(Actor a,String no){return reserveEvidence(a,no,purpose(a));}
  @Override public String reserveEvidence(Actor a, String no,String evidencePurpose) {
    if (a.operator()
        ? !Set.of("PLATFORM_RECEIPT", "REFUND_PROOF", "RECONCILIATION").contains(evidencePurpose)
        : !"BUYER_RECEIPT".equals(evidencePurpose)) throw denied();
    var s = snapshot(row(a, no, false));
    String key =
        media.newKey(scope(a, s, evidencePurpose), UUID.randomUUID().toString().replace("-", ""));
    write(
        () ->
            db.update(
                "INSERT INTO mch_plan_purchase_evidence VALUES(?,?,?,?,?,?)",
                TENANT,
                no,
                key,
                a.userNo(),
                a.subjectKind(),
                evidencePurpose));
    return key;
  }

  @Override
  public void uploadEvidence(Actor a, String no, String key, PrivateEvidencePort.Upload upload) {
    var scope = reserved(a, no, key, true);
    if (upload == null
        || upload.size() < 1
        || upload.size() > 10 * 1024 * 1024
        || !Set.of("image/jpeg", "image/png").contains(upload.contentType())) throw bad();
    if (media.stored(scope, key)) return;
    media.store(scope, key, upload);
  }

  @Override
  public String readEvidence(Actor a, String no, String key) {
    var scope = reserved(a, no, key, !a.operator());
    if (!media.stored(scope, key)) throw bad();
    return media.signedRead(scope, key);
  }

  private void requireEvidence(Actor a,String no,String key){
    requireEvidence(a, no, key, purpose(a));
  }
  private void requireEvidence(Actor a,String no,String key,String expected){var scope=reserved(a,no,key,true);if(!expected.equals(scope.purpose())||!media.stored(scope,key))throw bad();}
  @Override public void verifyRefundEvidence(Actor a,String no,String key){operator(a);
    requireEvidence(a, no, key, "REFUND_PROOF");
  }


  private ClaimView claimView(Map<String, Object> r) {
    return new ClaimView(
        str(r, "claim_no"),
        str(r, "status"),
        decode(r.get("command_json"), Claim.class),
        str(r, "review_note"));
  }

  private ActionView actionView(Map<String, Object> r) {
    String reason =
        "VOID".equals(str(r, "kind"))
            ? decode(r.get("command_json"), VoidCommand.class).reason()
            : decode(r.get("command_json"), Confirm.class).reason();
    return new ActionView(
        str(r, "action_no"),
        str(r, "status"),
        str(r, "payment_no"),
        r.get("failure_code") == null ? reason : str(r, "failure_code"));
  }

  @Override
  public ClaimView submit(Actor a, String no, Claim c) {
    if (a.operator()) throw denied();
    validMoney(c.amount(), true);
    validTime(c.paidAt());
    requireEvidence(a, no, c.evidenceKey());
    return write(
        () -> {
          var order = row(a, no, true);
          var old =
              one(
                  "SELECT * FROM mch_plan_purchase_claim WHERE tenant_no=? AND order_no=? AND"
                      + " claim_no=?",
                  TENANT,
                  no,
                  c.claimNo());
          if (old != null) {
            if (!decode(old.get("command_json"), Claim.class).equals(c)) throw bad();
            return claimView(old);
          }
          if (!Set.of("AWAITING_PAYMENT", "CANCELLATION_REQUESTED", "CANCELLED", "REFUNDED")
              .contains(str(order, "status"))) throw bad();
          db.update(
              "INSERT INTO mch_plan_purchase_claim(tenant_no,order_no,claim_no,command_json,status)"
                  + " VALUES(?,?,?,?,'SUBMITTED')",
              TENANT,
              no,
              c.claimNo(),
              encode(c));
          db.update(
              "UPDATE mch_plan_purchase SET status='SUBMITTED' WHERE tenant_no=? AND order_no=?",
              TENANT,
              no);
          return new ClaimView(c.claimNo(), "SUBMITTED", c, null);
        });
  }

  @Override
  public ClaimView decline(Actor a, String no, String claimNo, String reason) {
    operator(a);
    if (reason == null || reason.isBlank() || reason.length() > 500) throw bad();
    return write(
        () -> {
          var order = row(a, no, true);
          var claim =
              one(
                  "SELECT * FROM mch_plan_purchase_claim WHERE tenant_no=? AND order_no=? AND"
                      + " claim_no=?",
                  TENANT,
                  no,
                  claimNo);
          if (claim == null) throw bad();
          if ("DECLINED".equals(str(claim, "status")) && reason.equals(str(claim, "review_note")))
            return claimView(claim);
          if (!"SUBMITTED".equals(str(order, "status"))
              || !"SUBMITTED".equals(str(claim, "status"))) throw bad();
          db.update(
              "UPDATE mch_plan_purchase_claim SET status='DECLINED',review_note=? WHERE tenant_no=?"
                  + " AND order_no=? AND claim_no=?",
              reason,
              TENANT,
              no,
              claimNo);
          db.update(
              "UPDATE mch_plan_purchase SET status='AWAITING_PAYMENT' WHERE tenant_no=? AND"
                  + " order_no=?",
              TENANT,
              no);
          return new ClaimView(
              claimNo, "DECLINED", decode(claim.get("command_json"), Claim.class), reason);
        });
  }

  @Override
  public ActionView confirm(Actor a, String no, Confirm c) {
    operator(a);
    validMoney(c.amount(), true);
    validTime(c.paidAt());
    requireEvidence(a, no, c.evidenceKey());
    if (c.reason() == null || c.reason().isBlank()) throw bad();
    write(
        () -> {
          var order = row(a, no, true);
          var old =
              one(
                  "SELECT * FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND"
                      + " action_no=?",
                  TENANT,
                  no,
                  c.actionNo());
          if (old != null) {
            if (!"CONFIRM".equals(str(old, "kind"))
                || !a.userNo().equals(str(old, "operator_no"))
                || !decode(old.get("command_json"), Confirm.class).equals(c)) throw bad();
            return null;
          }
          if(c.claimNo()==null){
            if(!Set.of("AWAITING_PAYMENT","CANCELLATION_REQUESTED","CANCELLED","REFUNDED").contains(str(order,"status"))
                ||!"OPS".equals(snapshot(order).creatorKind())&&!Set.of("CANCELLED","REFUNDED").contains(str(order,"status")))throw bad();
          }else{
            if(!"SUBMITTED".equals(str(order,"status"))||one("SELECT claim_no FROM mch_plan_purchase_claim WHERE tenant_no=? AND order_no=? AND claim_no=? AND status='SUBMITTED'",TENANT,no,c.claimNo())==null)throw bad();
          }
          db.update(
              "INSERT INTO"
                  + " mch_plan_purchase_action(tenant_no,order_no,action_no,operator_no,command_json,confirmed_at,status)"
                  + " VALUES(?,?,?,?,?,?,'ACCEPTED')",
              TENANT,
              no,
              c.actionNo(),
              a.userNo(),
              encode(c),
              clock.millis());
          db.update(
              "UPDATE mch_plan_purchase SET status='CONFIRMING' WHERE tenant_no=? AND order_no=?",
              TENANT,
              no);
          return null;
        });
    advance(no, c.actionNo());
    return actionView(
        one(
            "SELECT * FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND"
                + " action_no=?",
            TENANT,
            no,
            c.actionNo()));
  }

  private String refusedClaimStatus(Map<String,Object> order,Confirm c){
    if(c.claimNo()!=null)return "SUBMITTED";
    if(order.get("closure_json")!=null)return decode(order.get("closure_json"),VoidAction.class).returnedFunds()?"REFUNDED":"CANCELLED";
    return "AWAITING_PAYMENT";
  }
  private void advance(String no, String actionNo) {
    var order = one("SELECT * FROM mch_plan_purchase WHERE tenant_no=? AND order_no=?", TENANT, no);
    var s = snapshot(order);
    var action =
        one(
            "SELECT * FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND"
                + " action_no=?",
            TENANT,
            no,
            actionNo);
    if (action == null || !"ACCEPTED".equals(str(action, "status"))) return;
    var c = decode(action.get("command_json"), Confirm.class);
    long accepted = number(action, "confirmed_at");
    OfflineCollectionPort.ManualResult result;
    try {
      result =
          money.confirm(
              new OfflineCollectionPort.ManualPayment(
                  s.intent(),
                  str(action, "operator_no"),
                  c.amount(),
                  c.paidAt(),
                  s.intent().platformMerchantNo(),
                  c.bankReference(),
                  c.evidenceKey(),
                  false,
                  Instant.ofEpochMilli(accepted)));
    } catch (IllegalArgumentException refusal) {
      reject(no, actionNo, refusal, refusedClaimStatus(order,c), false);
      return;
    }
    if (result == null
        || result.paymentNo() == null
        || !Set.of("MATCHED", "LATE", "UNMATCHED").contains(result.disposition()))
      throw new IllegalStateException("PAYMENT_RESULT_UNKNOWN");
    write(
        () -> {
          // Entity -> subscription -> order, the same ownership barrier as creation/quota writes.
          var identity =
              one(
                  "SELECT owner_user_no,market,deleted,ownership_change_no FROM mch_entity WHERE"
                      + " tenant_no=? AND entity_no=? FOR UPDATE",
                  TENANT,
                  s.entityNo());
          var plan =
              one(
                  "SELECT * FROM mch_entity_plan WHERE tenant_no=? AND entity_no=? AND deleted=0"
                      + " FOR UPDATE",
                  TENANT,
                  s.entityNo());
          var current =
              one(
                  "SELECT * FROM mch_plan_purchase WHERE tenant_no=? AND order_no=? FOR UPDATE",
                  TENANT,
                  no);
          var live =
              one(
                  "SELECT * FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND"
                      + " action_no=? FOR UPDATE",
                  TENANT,
                  no,
                  actionNo);
          if (!"ACCEPTED".equals(str(live, "status"))) return null;
          String status = "LATE".equals(result.disposition()) ? "REVIEW_REQUIRED" : "UNMATCHED";
          if ("MATCHED".equals(result.disposition())) {
            var entity = identity;
            boolean baseline =
                Objects.equals(s.previousPlan(), plan == null ? "FREE" : str(plan, "plan_code"))
                    && Objects.equals(s.previousExpiry(), number(plan, "expire_at"))
                    && Objects.equals(
                        s.previousGrant(), plan == null ? "SELF_PAID" : str(plan, "granted_by"));
            if (entity == null
                || !currency(entity).equals(s.offer().price().currency())
                || bool(entity, "deleted")
                || entity.get("ownership_change_no") != null
                || !Objects.equals(
                    s.ownerAccountNo(),
                    str(
                        ownerMembership(
                            new Actor(s.buyerNo(), s.entityNo(), s.storeNo(), false, s.buyerKind()),
                            true),
                        "mch_account_no"))
                || !baseline) status = "REVIEW_REQUIRED";
            else {
              long expiry =
                  EntityPlanPurchasePolicy.expiry(
                      s.previousPlan(),
                      s.previousExpiry(),
                      s.previousGrant(),
                      s.offer().planCode(),
                      s.offer().days(),
                      accepted);
              if (plan == null)
                db.update(
                    "INSERT INTO"
                        + " mch_entity_plan(entity_no,plan_code,store_quota,staff_quota,cross_store_stats,status,start_at,expire_at,granted_by,trial_used,tenant_no,created_at,updated_at,version,deleted)"
                        + " VALUES(?,?,?,?,?,'ACTIVE',?,?,'SELF_PAID',0,?,NOW(),NOW(),0,0)",
                    s.entityNo(),
                    s.offer().planCode(),
                    s.offer().storeQuota(),
                    s.offer().staffQuota(),
                    s.offer().crossStoreStats(),
                    accepted,
                    expiry,
                    TENANT);
              else
                db.update(
                    "UPDATE mch_entity_plan SET"
                        + " plan_code=?,store_quota=?,staff_quota=?,cross_store_stats=?,status='ACTIVE',start_at=COALESCE(start_at,?),expire_at=?,granted_by='SELF_PAID',downgraded_at=NULL,version=version+1,updated_at=NOW()"
                        + " WHERE tenant_no=? AND entity_no=? AND deleted=0",
                    s.offer().planCode(),
                    s.offer().storeQuota(),
                    s.offer().staffQuota(),
                    s.offer().crossStoreStats(),
                    accepted,
                    expiry,
                    TENANT,
                    s.entityNo());
              db.update(
                  "UPDATE mch_store SET"
                      + " status='ACTIVE',plan_suspended=0,version=version+1,updated_at=NOW() WHERE"
                      + " tenant_no=? AND entity_no=? AND plan_suspended=1 AND deleted=0",
                  TENANT,
                  s.entityNo());
              status = "APPLIED";
            }
          }
          db.update(
              "UPDATE mch_plan_purchase_action SET status=?,payment_no=? WHERE tenant_no=? AND"
                  + " order_no=? AND action_no=?",
              status,
              result.paymentNo(),
              TENANT,
              no,
              actionNo);
          db.update(
              "UPDATE mch_plan_purchase_claim SET status=?,review_note=? WHERE tenant_no=? AND"
                  + " order_no=? AND claim_no=?",
              status,
              c.reason(),
              TENANT,
              no,
              c.claimNo());
          db.update(
              "UPDATE mch_plan_purchase SET status=?,payment_no=?,open_entity=? WHERE tenant_no=?"
                  + " AND order_no=?",
              status,
              result.paymentNo(),
              "APPLIED".equals(status) ? null : str(current, "open_entity"),
              TENANT,
              no);
          return null;
        });
  }

  @Override
  public OrderView recover(Actor a, String no) {
    operator(a);
    var r = row(a, no, false);
    if ("PREPARED".equals(str(r, "status"))) return issue(a, no);
    if ("CLOSING".equals(str(r, "status"))) {
      advanceVoid(no);
      return order(a, no);
    }
    for (var action :
        db.queryForList(
            "SELECT action_no FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND"
                + " status='ACCEPTED' AND kind='CONFIRM'",
            TENANT,
            no)) advance(no, str(action, "action_no"));
    return order(a, no);
  }

  @Override public RecoverySweep recoverPending(String after,int limit){
    if(limit<1||limit>200)throw bad();
    var rows=db.queryForList("SELECT order_no,status,snapshot_json FROM mch_plan_purchase WHERE tenant_no=? AND order_no>? AND status IN ('PREPARED','CONFIRMING','CLOSING') ORDER BY order_no LIMIT ?",TENANT,after==null?"":after,limit);int failed=0;
    for(var r:rows)try{
      String no=str(r,"order_no");
      if("PREPARED".equals(str(r,"status"))){var intent=snapshot(r).intent();var result=payments.create(intent);if(result==null||result.status()!=AsyncStatus.PENDING||!intent.amount().equals(result.amount())||!intent.orderNo().equals(result.orderNo())||!intent.platformMerchantNo().equals(result.platformMerchantNo()))throw new IllegalStateException("PAYMENT_ISSUANCE_UNKNOWN");write(()->db.update("UPDATE mch_plan_purchase SET status='AWAITING_PAYMENT' WHERE tenant_no=? AND order_no=? AND status='PREPARED'",TENANT,no));}
      else if("CLOSING".equals(str(r,"status")))advanceVoid(no);
      else for(var action:db.queryForList("SELECT action_no FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND status='ACCEPTED' AND kind='CONFIRM'",TENANT,no))advance(no,str(action,"action_no"));
    }catch(RuntimeException retry){failed++;}
    return new RecoverySweep(rows.size(),failed,rows.isEmpty()?after:str(rows.getLast(),"order_no"));
  }

  public record VoidAction(VoidCommand command,String operatorNo,long acceptedAt,String previousStatus,boolean returnedFunds,Map<String,String> dispositions) {
    public VoidAction {dispositions=dispositions==null?Map.of():Map.copyOf(dispositions);}
  }

  @Override
  public OrderView cancel(Actor a, String no) {
    if (a.operator()) throw bad();
    write(
        () -> {
          var r = row(a, no, true);
          if ("CANCELLATION_REQUESTED".equals(str(r, "status"))
              || "CANCELLED".equals(str(r, "status"))) return null;
          if (!Set.of("PREPARED", "AWAITING_PAYMENT").contains(str(r, "status"))) throw bad();
          // Issued account instructions cannot prove no payment arrived. Only request a bank check.
          db.update(
              "UPDATE mch_plan_purchase SET status='CANCELLATION_REQUESTED' WHERE tenant_no=? AND"
                  + " order_no=?",
              TENANT,
              no);
          return null;
        });
    return order(a, no);
  }

  @Override
  public OrderView voidOrder(Actor a, String no, VoidCommand c) {
    operator(a);
    var initial=row(a,no,false);String priorStatus=str(initial,"status");
    boolean returning=Set.of("UNMATCHED","REVIEW_REQUIRED","REFUNDED").contains(priorStatus)||"CLOSING".equals(priorStatus)&&initial.get("closure_json")!=null&&decode(initial.get("closure_json"),VoidAction.class).returnedFunds();
    requireEvidence(a,no,c.evidenceKey(),returning?"RECONCILIATION":"PLATFORM_RECEIPT");
    Map<String,String> dispositions=new TreeMap<>();
    if(returning){
      var actual=money.receipts(TENANT,no);
      if(actual.isEmpty()||actual.stream().anyMatch(x->x.pendingRefund()||x.refundedMinor()!=x.amount().minor()))throw bad();
      var original=snapshot(initial).intent().paymentAttemptNo();
      for(var receipt:actual)if(!original.equals(receipt.paymentAttemptNo()))dispositions.put(receipt.paymentNo(),"VERIFIED_REFUNDED");
    }
    write(
        () -> {
          var r = row(a, no, true);
          var previous = r.get("closure_json");
          var prior =
              one(
                  "SELECT * FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND"
                      + " action_no=?",
                  TENANT,
                  no,
                  c.actionNo());
          if (prior != null) {
            if (!"VOID".equals(str(prior, "kind"))
                || !decode(prior.get("command_json"), VoidCommand.class).equals(c)
                || !a.userNo().equals(str(prior, "operator_no"))) throw bad();
            return null;
          }
          if (previous != null&&!returning) throw bad();
          if(!Set.of("PREPARED","AWAITING_PAYMENT","CANCELLATION_REQUESTED","UNMATCHED","REVIEW_REQUIRED","REFUNDED").contains(str(r,"status"))||!Objects.equals(priorStatus,str(r,"status")))throw bad();
          db.update(
              "INSERT INTO"
                  + " mch_plan_purchase_action(tenant_no,order_no,action_no,operator_no,command_json,confirmed_at,kind,status)"
                  + " VALUES(?,?,?,?,?,?,'VOID','ACCEPTED')",
              TENANT,
              no,
              c.actionNo(),
              a.userNo(),
              encode(c),
              clock.millis());
          db.update(
              "UPDATE mch_plan_purchase SET status='CLOSING',closure_json=? WHERE tenant_no=? AND"
                  + " order_no=?",
              encode(new VoidAction(c,a.userNo(),clock.millis(),priorStatus,returning,dispositions)),
              TENANT,
              no);
          return null;
        });
    advanceVoid(no);
    return order(a, no);
  }

  private void advanceVoid(String no) {
    var row = one("SELECT * FROM mch_plan_purchase WHERE tenant_no=? AND order_no=?", TENANT, no);
    if (!"CLOSING".equals(str(row, "status"))) return;
    var s = snapshot(row);
    var v = decode(row.get("closure_json"), VoidAction.class);
    var c = v.command();
    // Idempotently establish the original intent, including an interrupted issuance.
    payments.create(s.intent());
    var already=payments.query(new PaymentQuery(TENANT,no,s.intent().paymentAttemptNo(),s.intent()));
    if(v.returnedFunds()){
      var actual=money.receipts(TENANT,no);
      if(actual.isEmpty()||actual.stream().anyMatch(x->x.pendingRefund()||x.refundedMinor()!=x.amount().minor()))throw new IllegalStateException("REFUND_NOT_VERIFIED");
    }
    if(already==null||already.closureReference().isEmpty())try{
      money.reconcile(new OfflineCollectionPort.Reconciliation(s.intent(),v.operatorNo(),c.reference(),c.reason(),c.evidenceKey(),Instant.ofEpochMilli(v.acceptedAt()),v.dispositions()));
    }catch(IllegalArgumentException refusal){reject(no,c.actionNo(),refusal,v.previousStatus()==null?"CANCELLATION_REQUESTED":v.previousStatus(),true);return;}
    var closed=payments.query(new PaymentQuery(TENANT,no,s.intent().paymentAttemptNo(),s.intent()));
    if(closed==null||closed.closureReference().isEmpty()||!closed.definitivelyClosed()&&!(v.returnedFunds()&&closed.status()==AsyncStatus.SUCCEEDED))throw new IllegalStateException("PAYMENT_CLOSURE_UNKNOWN");
    write(
        () -> {
          db.update(
              "UPDATE mch_plan_purchase_action SET status='APPLIED' WHERE tenant_no=? AND"
                  + " order_no=? AND action_no=? AND status='ACCEPTED'",
              TENANT,
              no,
              c.actionNo());
          return db.update(
              "UPDATE mch_plan_purchase SET status=?,open_entity=NULL WHERE tenant_no=?"
                  + " AND order_no=? AND status='CLOSING'",
              v.returnedFunds()?"REFUNDED":"CANCELLED",
              TENANT,
              no);
        });
  }

  private void reject(
      String no, String actionNo, IllegalArgumentException refusal, String nextStatus, boolean clearClosure) {
    String code =
        refusal.getMessage() != null && refusal.getMessage().matches("[A-Z0-9_]{1,64}")
            ? refusal.getMessage()
            : "OFFLINE_REJECTED";
    write(
        () -> {
          one(
              "SELECT order_no FROM mch_plan_purchase WHERE tenant_no=? AND order_no=? FOR UPDATE",
              TENANT,
              no);
          int changed =
              db.update(
                  "UPDATE mch_plan_purchase_action SET status='REJECTED',failure_code=? WHERE"
                      + " tenant_no=? AND order_no=? AND action_no=? AND status='ACCEPTED'",
                  code,
                  TENANT,
                  no,
                  actionNo);
          if (changed == 1)
            db.update(
                "UPDATE mch_plan_purchase SET status=?,closure_json=IF(?,NULL,closure_json) WHERE tenant_no=? AND"
                    + " order_no=?",
                nextStatus,
                clearClosure,
                TENANT,
                no);
          return null;
        });
  }

  @Override
  public Optional<Boolean> recoverPayment(String tenant, String no, String paymentNo) {
    if (!TENANT.equals(tenant)) return Optional.empty();
    var r = one("SELECT * FROM mch_plan_purchase WHERE tenant_no=? AND order_no=?", TENANT, no);
    if (r == null) return Optional.empty();
    for (var action :
        db.queryForList(
            "SELECT action_no FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND"
                + " status='ACCEPTED' AND kind='CONFIRM'",
            TENANT,
            no)) advance(no, str(action, "action_no"));
    var after =
        one(
            "SELECT status,payment_no FROM mch_plan_purchase WHERE tenant_no=? AND order_no=?",
            TENANT,
            no);
    return Optional.of(one("SELECT action_no FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND payment_no=? AND status IN ('APPLIED','REVIEW_REQUIRED','UNMATCHED') LIMIT 1",TENANT,no,paymentNo)!=null);
  }
}
