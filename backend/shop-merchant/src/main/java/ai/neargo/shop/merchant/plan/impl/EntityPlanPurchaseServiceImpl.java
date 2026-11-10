package ai.neargo.shop.merchant.plan.impl;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.offline.OfflineCollectionTerms;
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
      PaymentIntentCommand intent) {}

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
            "SELECT * FROM mch_entity WHERE tenant_no=? AND entity_no=? AND deleted=0",
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
  public OrderView buy(Actor a, Buy c) {
    var entity = authorize(a);
    if (a.operator()) throw denied();
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
            "SELECT order_no,request_json FROM mch_plan_purchase WHERE tenant_no=? AND buyer_no=?"
                + " AND buyer_kind=? AND request_no=?",
            TENANT,
            a.userNo(),
            a.subjectKind(),
            c.requestNo());
    if (replay != null) {
      if (!decode(replay.get("request_json"), Buy.class).equals(c)) throw bad();
      return issue(a, str(replay, "order_no"));
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
              authorize(a);
              var owner = ownerMembership(a, true);
              if (owner == null) throw denied();
              var old =
                  one(
                      "SELECT order_no,request_json FROM mch_plan_purchase WHERE tenant_no=? AND"
                          + " buyer_no=? AND buyer_kind=? AND request_no=? FOR UPDATE",
                      TENANT,
                      a.userNo(),
                      a.subjectKind(),
                      c.requestNo());
              if (old != null) {
                if (!decode(old.get("request_json"), Buy.class).equals(c)) throw bad();
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
                      intent);
              db.update(
                  "INSERT INTO"
                      + " mch_plan_purchase(tenant_no,order_no,entity_no,buyer_no,buyer_kind,request_no,request_json,snapshot_json,status,open_entity,created_at)"
                      + " VALUES(?,?,?,?,?,?,?,?,'PREPARED',?,?)",
                  TENANT,
                  no,
                  a.entityNo(),
                  a.userNo(),
                  a.subjectKind(),
                  c.requestNo(),
                  encode(c),
                  encode(frozen),
                  a.entityNo(),
                  now);
              return no;
            });
    return issue(a, order);
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
        actions);
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
                || !purpose(a).equals(str(r, "purpose")))) throw denied();
    return new PrivateEvidencePort.Scope(
        new StoreRef(TENANT, s.storeNo()),
        s.entityNo(),
        no,
        "PLATFORM_RECEIPT".equals(str(r, "purpose")) ? SubjectKind.OPERATOR : SubjectKind.ACCOUNT,
        str(r, "submitter_no"),
        str(r, "purpose"));
  }

  @Override
  public String reserveEvidence(Actor a, String no) {
    var s = snapshot(row(a, no, false));
    String key =
        media.newKey(scope(a, s, purpose(a)), UUID.randomUUID().toString().replace("-", ""));
    write(
        () ->
            db.update(
                "INSERT INTO mch_plan_purchase_evidence VALUES(?,?,?,?,?,?)",
                TENANT,
                no,
                key,
                a.userNo(),
                a.subjectKind(),
                purpose(a)));
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

  private void requireEvidence(Actor a, String no, String key) {
    var scope = reserved(a, no, key, true);
    if (!media.stored(scope, key)) throw bad();
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
          if (!Set.of("AWAITING_PAYMENT", "CANCELLATION_REQUESTED", "CANCELLED")
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
          if (!"SUBMITTED".equals(str(order, "status"))) throw bad();
          if (one(
                  "SELECT claim_no FROM mch_plan_purchase_claim WHERE tenant_no=? AND order_no=?"
                      + " AND claim_no=? AND status='SUBMITTED'",
                  TENANT,
                  no,
                  c.claimNo())
              == null) throw bad();
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
      reject(no, actionNo, refusal, "SUBMITTED");
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

  public record VoidAction(VoidCommand command, String operatorNo, long acceptedAt) {}

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
    requireEvidence(a, no, c.evidenceKey());
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
          if (previous != null) throw bad();
          if (!Set.of("PREPARED", "AWAITING_PAYMENT", "CANCELLATION_REQUESTED")
              .contains(str(r, "status"))) throw bad();
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
              encode(new VoidAction(c, a.userNo(), clock.millis())),
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
    try {
      money.reconcile(
          new OfflineCollectionPort.Reconciliation(
              s.intent(),
              v.operatorNo(),
              c.reference(),
              c.reason(),
              c.evidenceKey(),
              Instant.ofEpochMilli(v.acceptedAt()),
              Map.of()));
    } catch (IllegalArgumentException refusal) {
      reject(no, c.actionNo(), refusal, "CANCELLATION_REQUESTED");
      return;
    }
    var closed =
        payments.query(new PaymentQuery(TENANT, no, s.intent().paymentAttemptNo(), s.intent()));
    if (closed == null || !closed.definitivelyClosed() || closed.closureReference().isEmpty())
      throw new IllegalStateException("PAYMENT_CLOSURE_UNKNOWN");
    write(
        () -> {
          db.update(
              "UPDATE mch_plan_purchase_action SET status='APPLIED' WHERE tenant_no=? AND"
                  + " order_no=? AND action_no=? AND status='ACCEPTED'",
              TENANT,
              no,
              c.actionNo());
          return db.update(
              "UPDATE mch_plan_purchase SET status='CANCELLED',open_entity=NULL WHERE tenant_no=?"
                  + " AND order_no=? AND status='CLOSING'",
              TENANT,
              no);
        });
  }

  private void reject(
      String no, String actionNo, IllegalArgumentException refusal, String nextStatus) {
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
                "UPDATE mch_plan_purchase SET status=?,closure_json=NULL WHERE tenant_no=? AND"
                    + " order_no=?",
                nextStatus,
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
    return Optional.of(
        paymentNo.equals(str(after, "payment_no"))
            && Set.of("APPLIED", "REVIEW_REQUIRED").contains(str(after, "status")));
  }
}
