package ai.neargo.shop.merchant.plan.impl;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.offline.OfflineCollectionTerms;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.plan.*;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import ai.neargo.shop.merchant.service.StoreOperationalReviewService;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Frozen entity purchases; all ledger/media calls run outside the business transaction.
 *
 * <p><b>待退役，并入商城</b>（2026-11-26 用户定，商城会话处理）：见本包的 {@code package-info}。不在本分支改写。
 */
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
  private final StoreOperationalReviewService reviews;

  private final EntityPlanMallReceipts mallReceipts;
  private final EntityPlanOfferCatalog catalog;
  private final EntityPlanCheckout checkout;

  @org.springframework.beans.factory.annotation.Value("${shop.store.max-per-entity:1}")
  private int fallbackStoreQuota=1;

  public record QuotaBefore(long grantedId,long grantedVersion,String planCode,int stores,int staff,
      boolean crossStore,String status,Long start,Long expiry,String grant,Long downgraded,Long storeOverride,Long staffOverride,Boolean overridesKnown) {}

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
      String creatorKind, EntityPlanCheckout.Quote quote, QuotaBefore quotaBefore,String refundNo,RefundDecision refundDecision) {
    Snapshot issued(PaymentIntentCommand payment) {
      return new Snapshot(entityNo,storeNo,buyerNo,buyerKind,ownerAccountNo,offer,previousPlan,previousExpiry,
          previousGrant,payment,creatorNo,creatorKind,quote,quotaBefore,refundNo,refundDecision);
    }
  }

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
      String profileNo, StoreOperationalReviewService reviews) {
    this(db, manager, json, payments, money, media, policies, ownership, opsVisible, clock, profileNo, reviews, null);
  }

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
      String profileNo, StoreOperationalReviewService reviews, JdbcTemplate mall) {
    this(db,manager,json,payments,money,media,policies,ownership,opsVisible,clock,profileNo,reviews,mall,null);
  }

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
      String profileNo, StoreOperationalReviewService reviews, JdbcTemplate mall, EntityPlanCheckout checkout) {
    this.checkout = checkout;
    this.catalog = mall == null ? null : new EntityPlanOfferCatalog(mall);
    this.mallReceipts = mall == null ? null : new EntityPlanMallReceipts(mall, json);
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
    this.reviews = reviews;
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
                    || o.enabled() && (checkout == null || o.catalog() != null)
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
    var skuVersion = c.skuVersionNo();
    if (catalog == null && skuVersion != null && !skuVersion.isBlank()) throw bad();
    if (checkout != null && c.enabled() && (skuVersion == null || skuVersion.isBlank())) throw bad();
    var binding = !c.enabled() || skuVersion == null || skuVersion.isBlank() ? null :
        catalog.resolve(TENANT,skuVersion,c.planCode(),c.price(),c.days(),clock.millis());
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
                  c.enabled(), !c.enabled() && prior != null ? decode(prior.get("snapshot_json"), Offer.class).catalog() : binding);
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
                    + " status IN ('ACTIVE','PENDING_OPEN') AND deleted=0 ORDER BY store_no",
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
                + " AND status IN ('ACTIVE','PENDING_OPEN') AND deleted=0",
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
    if (offer == null || !currency(entity).equals(offer.price().currency())) throw bad();
    validMoney(offer.price(), true);
    OfflineCollectionTerms terms =
        money
            .terms(TENANT, profileNo, offer.price().currency())
            .orElseThrow(EntityPlanPurchaseServiceImpl::bad);
    EntityPlanCheckout.Quote quote = null;
    if (checkout != null) {
      var bound = offer.catalog();
      if (bound == null || !bound.equals(catalog.resolve(TENANT,bound.skuVersionNo(),offer.planCode(),
          offer.price(),offer.days(),clock.millis()))) throw bad();
      quote = checkout.quote(creator,a,offer);
    }
    var purchaseQuote = quote;
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
                              + " store_no=? AND status IN ('ACTIVE','PENDING_OPEN') AND deleted=0",
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
              String no = purchaseQuote == null ? "EQ" + UUID.randomUUID().toString().replace("-", "") : purchaseQuote.orderNo();
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
                      creatorKind(creator),purchaseQuote,null,null,null);
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
    issuePayment(snapshot(row),true);
    return order(a, no);
  }

  private void issuePayment(Snapshot snapshot, boolean interactive) {
    var intent = snapshot.intent();
    if (snapshot.quote() != null) {
      if (checkout.discardUnplaced(snapshot.quote(),true)) {
        db.update("UPDATE mch_plan_purchase SET status='CANCELLED',open_entity=NULL WHERE tenant_no=? AND order_no=? AND status='PREPARED'",
            TENANT,intent.orderNo());
        return;
      }
      var payment = checkout.issue(snapshot.quote(),snapshot.storeNo(),"OPS".equals(snapshot.creatorKind()),interactive);
      if (!intent.orderNo().equals(payment.orderNo()) || !intent.amount().equals(payment.amount())
          || !intent.platformMerchantNo().equals(payment.platformMerchantNo())) throw new IllegalStateException("ENTITY_PLAN_PAYMENT_CONFLICT");
      db.update("UPDATE mch_plan_purchase SET snapshot_json=?,status='AWAITING_PAYMENT' WHERE tenant_no=? AND order_no=? AND status='PREPARED'",
          encode(snapshot.issued(payment)),TENANT,intent.orderNo());
      return;
    }
    if (mallReceipts != null) mallReceipts.prepare(intent);
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
                intent.orderNo()));
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
        "ENTITY_GRANTED".equals(str(r,"status")) ? "PAID_PENDING" : str(r,"status"),
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
          if (snapshot(order).refundNo()!=null) throw bad();
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
                      + " action_no=? FOR UPDATE",
                  TENANT,
                  no,
                  c.actionNo());
          if (old != null) {
            if (!"CONFIRM".equals(str(old, "kind"))
                || !a.userNo().equals(str(old, "operator_no"))
                || !decode(old.get("command_json"), Confirm.class).equals(c)) throw bad();
            return null;
          }
          if(snapshot(order).refundNo()!=null) throw bad();
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
    try {
      advance(no, c.actionNo());
    } catch (org.springframework.dao.DataAccessException failure) {
      var pending = one("SELECT * FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND action_no=?",
          TENANT, no, c.actionNo());
      if (pending == null || !Set.of("ENTITY_GRANTED","PAID_PENDING").contains(str(pending, "status"))) throw failure;
      return actionView(pending);
    }
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
    if (action != null && "ENTITY_GRANTED".equals(str(action,"status"))) {
      finishDigitalGrant(no,actionNo);
      return;
    }
    if (action == null || !Set.of("ACCEPTED", "PAID_PENDING").contains(str(action, "status"))) return;
    var c = decode(action.get("command_json"), Confirm.class);
    long accepted = number(action, "confirmed_at");
    OfflineCollectionPort.ManualResult result;
    if ("PAID_PENDING".equals(str(action, "status"))) {
      result = new OfflineCollectionPort.ManualResult("MATCHED", str(action, "payment_no"));
    } else {
      var command = new OfflineCollectionPort.ManualPayment(s.intent(), str(action, "operator_no"),
          c.amount(), c.paidAt(), s.intent().platformMerchantNo(), c.bankReference(),
          c.evidenceKey(), false, Instant.ofEpochMilli(accepted));
      var saved = s.quote() != null || mallReceipts == null ? null : mallReceipts.find(actionNo, command);
      if (saved != null) {
        result = saved;
      } else {
        try {
          result = money.confirm(command);
        } catch (IllegalArgumentException refusal) {
          reject(no, actionNo, refusal, refusedClaimStatus(order,c), false);
          return;
        }
        if (result == null || result.paymentNo() == null
            || !Set.of("MATCHED", "LATE", "UNMATCHED").contains(result.disposition()))
          throw new IllegalStateException("PAYMENT_RESULT_UNKNOWN");
        if (s.quote() == null && mallReceipts != null && "MATCHED".equals(result.disposition()))
          mallReceipts.save(actionNo, command, result);
      }
      if ("MATCHED".equals(result.disposition())) {
        write(() -> {
          one("SELECT order_no FROM mch_plan_purchase WHERE tenant_no=? AND order_no=? FOR UPDATE", TENANT, no);
          if (db.update("UPDATE mch_plan_purchase_action SET status='PAID_PENDING',payment_no=?"
              + " WHERE tenant_no=? AND order_no=? AND action_no=? AND status='ACCEPTED'",
              result.paymentNo(), TENANT, no, actionNo) == 1)
            db.update("UPDATE mch_plan_purchase SET status='PAID_PENDING',payment_no=? WHERE tenant_no=? AND order_no=?",
                result.paymentNo(), TENANT, no);
          return null;
        });
      }
    }
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
          if ("MATCHED".equals(result.disposition())) {
            if (!"PAID_PENDING".equals(str(live, "status")) || !result.paymentNo().equals(str(live,"payment_no"))) return null;
          } else if (!"ACCEPTED".equals(str(live, "status"))) return null;
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
                      + " status=CASE WHEN status='SUSPENDED' THEN status WHEN operational_review IN ('PENDING','REJECTED') THEN 'PENDING_OPEN' ELSE 'ACTIVE' END,plan_suspended=0,version=version+1,updated_at=NOW() WHERE"
                      + " tenant_no=? AND entity_no=? AND plan_suspended=1 AND deleted=0",
                  TENANT,
                  s.entityNo());
              if (s.quote()!=null) {
                var applied=one("SELECT id,version FROM mch_entity_plan WHERE tenant_no=? AND entity_no=? AND deleted=0",TENANT,s.entityNo());
                var before=new QuotaBefore(number(applied,"id"),number(applied,"version"),s.previousPlan(),
                    plan==null?fallbackStoreQuota:number(plan,"store_quota").intValue(),
                    plan==null?0:number(plan,"staff_quota").intValue(),plan!=null && bool(plan,"cross_store_stats"),
                    plan==null?"ACTIVE":str(plan,"status"),number(plan,"start_at"),s.previousExpiry(),
                    s.previousGrant(),number(plan,"downgraded_at"),number(plan,"store_quota_override"),
                    number(plan,"staff_quota_override"),true);
                db.update("UPDATE mch_plan_purchase SET snapshot_json=JSON_SET(snapshot_json,'$.quotaBefore',CAST(? AS JSON)) WHERE tenant_no=? AND order_no=?",
                    encode(before),TENANT,no);
              }
              reviews.activatePaid(TENANT,s.entityNo(),s.storeNo(),"ENTITY",no,number(current,"created_at"),accepted);
              status = s.quote() == null ? "APPLIED" : "ENTITY_GRANTED";
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
              "ENTITY_GRANTED".equals(status) ? "PAID_PENDING" : status,
              c.reason(),
              TENANT,
              no,
              c.claimNo());
          db.update(
              "UPDATE mch_plan_purchase SET status=?,payment_no=?,open_entity=? WHERE tenant_no=?"
                  + " AND order_no=?",
              "ENTITY_GRANTED".equals(status) ? "PAID_PENDING" : status,
              result.paymentNo(),
              "APPLIED".equals(status) ? null : str(current, "open_entity"),
              TENANT,
              no);
          return null;
        });
    if (s.quote() != null) {
      var state=one("SELECT status FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND action_no=?",TENANT,no,actionNo);
      if ("ENTITY_GRANTED".equals(str(state,"status"))) finishDigitalGrant(no,actionNo);
      else checkout.refreshPayment(no);
    }
  }

  private void finishDigitalGrant(String no,String actionNo) {
    if (!checkout.fulfill(no)) return;
    write(()->{
      one("SELECT order_no FROM mch_plan_purchase WHERE tenant_no=? AND order_no=? FOR UPDATE",TENANT,no);
      if (db.update("UPDATE mch_plan_purchase_action SET status='APPLIED' WHERE tenant_no=? AND order_no=? AND action_no=? AND status='ENTITY_GRANTED'",TENANT,no,actionNo)==1) {
        db.update("UPDATE mch_plan_purchase SET status='APPLIED',open_entity=NULL WHERE tenant_no=? AND order_no=? AND status='PAID_PENDING'",TENANT,no);
        db.update("UPDATE mch_plan_purchase_claim SET status='APPLIED' WHERE tenant_no=? AND order_no=? AND status='PAID_PENDING'",TENANT,no);
      }
      return null;
    });
  }

  @Override
  public OrderView recover(Actor a, String no) {
    operator(a);
    var r = row(a, no, false);
    if (checkout!=null && snapshot(r).quote()!=null)
      for(var refund:checkout.refundedPlans(null,no,1)) reverseRefund(refund);
    if ("PREPARED".equals(str(r, "status"))) return issue(a, no);
    if ("CLOSING".equals(str(r, "status"))) {
      advanceVoid(no);
      return order(a, no);
    }
    for (var action :
        db.queryForList(
            "SELECT action_no FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND"
                + " status IN ('ACCEPTED','PAID_PENDING','ENTITY_GRANTED') AND kind='CONFIRM'",
            TENANT,
            no)) advance(no, str(action, "action_no"));
    return order(a, no);
  }

  @Override
  public RefundReview refundReview(Actor actor, String orderNo) {
    operator(actor);
    return write(() -> review(actor, orderNo, null));
  }

  @Override
  public RefundReview closeRefundReview(Actor actor, String orderNo, CloseRefundReview command) {
    operator(actor);
    if (command == null || command.reason() == null || command.reason().isBlank()
        || command.reason().length() > 500) throw bad();
    return write(() -> review(actor, orderNo, command));
  }

  private RefundReview review(Actor actor, String orderNo, CloseRefundReview command) {
    boolean closing = command != null;
    if (closing) one("SELECT entity_no FROM mch_entity WHERE tenant_no=? AND entity_no=? FOR UPDATE",
        TENANT, actor.entityNo());
    var plan = one("SELECT * FROM mch_entity_plan WHERE tenant_no=? AND entity_no=? AND deleted=0"
        + (closing ? " FOR UPDATE" : ""), TENANT, actor.entityNo());
    var purchase = row(actor, orderNo, closing);
    var snap = snapshot(purchase);
    String status = str(purchase, "status");
    if (!Set.of("REFUND_REVIEW", "REFUND_REVIEWED").contains(status) || snap.refundNo() == null) throw bad();
    var previous = snap.quotaBefore();
    var before = new ReviewQuota(snap.previousPlan(), previous == null ? null : (long) previous.stores(),
        previous == null ? null : (long) previous.staff(), previous == null ? null : previous.crossStore(),
        previous == null ? null : previous.storeOverride(), previous == null ? null : previous.staffOverride(),
        previous != null && Boolean.TRUE.equals(previous.overridesKnown()), snap.previousExpiry(), snap.previousGrant());
    var current = plan == null
        ? new ReviewQuota("FREE", (long) fallbackStoreQuota, 0L, false, null, null, true, null, "SELF_PAID")
        : new ReviewQuota(str(plan,"plan_code"), number(plan,"store_quota"), number(plan,"staff_quota"),
            bool(plan,"cross_store_stats"), number(plan,"store_quota_override"), number(plan,"staff_quota_override"),
            true, number(plan,"expire_at"), str(plan,"granted_by"));
    String revision = plan == null ? "fallback:" + fallbackStoreQuota : number(plan,"id") + ":" + number(plan,"version");
    var decision = snap.refundDecision();
    if (closing) {
      if (decision != null) {
        if (!decision.command().equals(command) || !decision.operatorNo().equals(actor.userNo())) throw bad();
      } else {
        if (!"REFUND_REVIEW".equals(status) || !snap.refundNo().equals(command.refundNo())
            || !revision.equals(command.quotaRevision())) throw bad();
        decision = new RefundDecision("KEEP_CURRENT", actor.userNo(), clock.instant(), command, current);
        status = "REFUND_REVIEWED";
        db.update("""
            UPDATE mch_plan_purchase SET status=?,snapshot_json=JSON_SET(snapshot_json,'$.refundDecision',CAST(? AS JSON))
            WHERE tenant_no=? AND entity_no=? AND order_no=?
            """, status, encode(decision), TENANT, actor.entityNo(), orderNo);
      }
    }
    return new RefundReview(orderNo,snap.storeNo(),snap.entityNo(),snap.offer(),snap.refundNo(),status,
        before,current,revision,decision);
  }

  @Override public RecoverySweep recoverRefunds(String after,int limit) {
    if(limit<1 || limit>200) throw bad();
    if(checkout==null) return new RecoverySweep(0,0,after);
    var rows=checkout.refundedPlans(after,null,limit);
    int failed=0;
    for (var refund : rows) {
      try {
        if (!reverseRefund(refund)) failed++;
      } catch (RuntimeException retry) {
        failed++;
      }
    }
    return new RecoverySweep(rows.size(),failed,rows.isEmpty()?after:rows.getLast().refundNo());
  }

  private boolean reverseRefund(EntityPlanCheckout.RefundedPlan refund) {
    if(!checkout.reversalDelivered(refund)) {
      db.update("""
          UPDATE mch_plan_purchase SET status='REFUND_PENDING'
          WHERE tenant_no=? AND entity_no=? AND order_no=? AND snapshot_json->>'$.storeNo'=?
            AND status IN ('APPLIED','PAID_PENDING')
          """,TENANT,refund.entityNo(),refund.orderNo(),refund.storeNo());
      return false;
    }
    String result=write(()->{
      var entity=one("SELECT ownership_change_no FROM mch_entity WHERE tenant_no=? AND entity_no=? AND deleted=0 FOR UPDATE",TENANT,refund.entityNo());
      var plan=one("SELECT * FROM mch_entity_plan WHERE tenant_no=? AND entity_no=? AND deleted=0 FOR UPDATE",TENANT,refund.entityNo());
      var purchase=one("SELECT * FROM mch_plan_purchase WHERE tenant_no=? AND order_no=? FOR UPDATE",TENANT,refund.orderNo());
      if(purchase==null) return null;
      var snap=snapshot(purchase);
      if(snap.quote()==null || !snap.entityNo().equals(refund.entityNo()) || !snap.storeNo().equals(refund.storeNo())) return null;
      String status=str(purchase,"status");
      if(refund.refundNo().equals(snap.refundNo())) return snap.refundDecision()==null ? status : "REFUND_REVIEW";
      if(!Set.of("APPLIED","PAID_PENDING","REFUND_PENDING").contains(status)) return null;
      var before=snap.quotaBefore();
      String outcome="REFUND_REVIEW";
      if(before!=null && entity!=null && entity.get("ownership_change_no")==null && plan!=null
          && before.grantedId()==number(plan,"id") && before.grantedVersion()==number(plan,"version")) {
        db.update("""
            UPDATE mch_entity_plan SET plan_code=?,store_quota=?,staff_quota=?,cross_store_stats=?,status=?,
              start_at=?,expire_at=?,granted_by=?,downgraded_at=?,version=version+1,updated_at=NOW()
            WHERE tenant_no=? AND entity_no=? AND deleted=0
            """,before.planCode(),before.stores(),before.staff(),before.crossStore(),before.status(),before.start(),
            before.expiry(),before.grant(),before.downgraded(),TENANT,refund.entityNo());
        outcome="REFUNDED";
      }
      db.update("UPDATE mch_plan_purchase SET status=?,open_entity=NULL,snapshot_json=JSON_SET(snapshot_json,'$.refundNo',?) WHERE tenant_no=? AND order_no=?",outcome,refund.refundNo(),TENANT,refund.orderNo());
      db.update("UPDATE mch_plan_purchase_action SET status='APPLIED' WHERE tenant_no=? AND order_no=? AND status='ENTITY_GRANTED'",TENANT,refund.orderNo());
      db.update("UPDATE mch_plan_purchase_claim SET status='APPLIED' WHERE tenant_no=? AND order_no=? AND status='PAID_PENDING'",TENANT,refund.orderNo());
      return outcome;
    });
    if(result==null) return false;
    checkout.acknowledgeReversal(refund,result);
    return true;
  }

  @Override public RecoverySweep recoverPending(String after,int limit){
    if(limit<1||limit>200)throw bad();
    var rows=db.queryForList("SELECT order_no,status,snapshot_json FROM mch_plan_purchase WHERE tenant_no=? AND order_no>? AND status IN ('PREPARED','CONFIRMING','PAID_PENDING','CLOSING') ORDER BY order_no LIMIT ?",TENANT,after==null?"":after,limit);int failed=0;
    for(var r:rows)try{
      String no=str(r,"order_no");
      if ("PREPARED".equals(str(r,"status"))) issuePayment(snapshot(r),false);
      else if("CLOSING".equals(str(r,"status")))advanceVoid(no);
      else for(var action:db.queryForList("SELECT action_no FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND status IN ('ACCEPTED','PAID_PENDING','ENTITY_GRANTED') AND kind='CONFIRM'",TENANT,no))advance(no,str(action,"action_no"));
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
          if(snapshot(r).refundNo()!=null) throw bad();
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
    if (s.quote() != null && checkout.discardUnplaced(s.quote(),false)) {
      finishVoid(no,v);
      return;
    }
    if (s.quote() != null && s.intent().orderNo().equals(s.intent().paymentAttemptNo())) {
      s = s.issued(checkout.issue(s.quote(),s.storeNo(),"OPS".equals(s.creatorKind()),false));
      db.update("UPDATE mch_plan_purchase SET snapshot_json=? WHERE tenant_no=? AND order_no=? AND status='CLOSING'",encode(s),TENANT,no);
    }
    if (s.quote() == null && mallReceipts != null) mallReceipts.prepare(s.intent());
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
    if (s.quote() == null && mallReceipts != null) mallReceipts.close(s.intent(), closed.closureReference().orElseThrow());
    if (s.quote() != null) checkout.refreshPayment(no); // Project the verified payment fence into the mall order.
    finishVoid(no,v);
  }

  private void finishVoid(String no, VoidAction v) {
    var c = v.command();
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
    if (checkout != null && snapshot(r).quote() != null && awaitingReceipt(str(r,"status"))) {
      checkout.confirmedReceipt(no,paymentNo).ifPresent(receipt -> write(()->{
        var current=one("SELECT * FROM mch_plan_purchase WHERE tenant_no=? AND order_no=? FOR UPDATE",TENANT,no);
        if (!awaitingReceipt(str(current,"status"))) return null;
        var confirm=receipt.confirmation();
        if (!snapshot(current).offer().price().equals(confirm.amount())) return null;
        // Mall and merchant review actions have independent caller-supplied identifiers.
        var actionNo="mall-"+java.util.UUID.nameUUIDFromBytes(paymentNo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        db.update("INSERT INTO mch_plan_purchase_action(tenant_no,order_no,action_no,operator_no,command_json,confirmed_at,status,payment_no) VALUES(?,?,?,?,?,?,'PAID_PENDING',?)",
            TENANT,no,actionNo,receipt.operatorNo(),encode(confirm),receipt.acceptedAt(),paymentNo);
        db.update("UPDATE mch_plan_purchase SET status='PAID_PENDING',payment_no=? WHERE tenant_no=? AND order_no=?",paymentNo,TENANT,no);
        return null;
      }));
    }
    for (var action :
        db.queryForList(
            "SELECT action_no FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND"
                + " status IN ('ACCEPTED','PAID_PENDING','ENTITY_GRANTED') AND kind='CONFIRM'",
            TENANT,
            no)) advance(no, str(action, "action_no"));
    return Optional.of(one("SELECT action_no FROM mch_plan_purchase_action WHERE tenant_no=? AND order_no=? AND payment_no=? AND status IN ('APPLIED','REVIEW_REQUIRED','UNMATCHED') LIMIT 1",TENANT,no,paymentNo)!=null);
  }

  private static boolean awaitingReceipt(String status) {
    return Set.of("AWAITING_PAYMENT","SUBMITTED","CANCELLATION_REQUESTED").contains(status);
  }
}
