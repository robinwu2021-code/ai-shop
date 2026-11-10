package ai.neargo.shop.merchant.plan;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.offline.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import ai.neargo.shop.merchant.plan.impl.EntityPlanPurchaseServiceImpl;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "PLAN_MYSQL_URL", matches = ".+")
class EntityPlanPurchaseMysqlTest {
  JdbcTemplate admin, db;
  String schema;
  EntityPlanPurchaseService svc;
  final Actor buyer = new Actor("U1", "E1", "S1", false), ops = new Actor("OP1", "E1", null, true);
  final Instant now = Instant.parse("2026-11-11T00:00:00Z");
  int receipts;
  boolean cut, mismatch, closed;
  String refusal;
  long currentTime;
  final Set<String> files = new HashSet<>();

  @BeforeEach
  void setup() throws Exception {
    String url = System.getenv("PLAN_MYSQL_URL");
    assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3308/?"));
    String user = System.getenv("PLAN_MYSQL_USER"), pass = System.getenv("PLAN_MYSQL_PASSWORD");
    currentTime = now.toEpochMilli();
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));
    schema = "plan_buy_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
    var ds = new DriverManagerDataSource(url.replace("/?", "/" + schema + "?"), user, pass);
    db = new JdbcTemplate(ds);
    db.execute(
        "CREATE TABLE mch_account(tenant_no varchar(32),mch_account_no varchar(64),entity_no"
            + " varchar(64),user_no varchar(64),is_owner boolean,status varchar(16),deleted"
            + " int,PRIMARY KEY(tenant_no,mch_account_no))");
    db.update(
        "INSERT INTO mch_account"
            + " VALUES('MAIN','MA1','E1','U1',1,'ACTIVE',0),('MAIN','MA2','E2','U2',1,'ACTIVE',0),('MAIN','U1','E1','OTHER',0,'ACTIVE',0)");
    db.execute(
        "CREATE TABLE mch_entity(tenant_no varchar(32),entity_no varchar(64),owner_user_no"
            + " varchar(64),market varchar(8),deleted int,ownership_change_no varchar(64) DEFAULT"
            + " NULL,PRIMARY KEY(tenant_no,entity_no))");
    db.execute(
        "CREATE TABLE mch_store(tenant_no varchar(32),store_no varchar(64),entity_no"
            + " varchar(64),status varchar(16),plan_suspended boolean,deleted int,version"
            + " bigint,updated_at datetime,PRIMARY KEY(tenant_no,store_no))");
    String old =
        Files.readString(
            Path.of("../shop-app/src/main/resources/db/migration/V150__merchant_plan.sql"));
    // Use the real plan DDL, with minimal independent entity/store fixtures.
    for (String table : List.of("sys_merchant_plan_def", "mch_entity_plan")) {
      int start = old.indexOf("CREATE TABLE IF NOT EXISTS " + table);
      int end = old.indexOf(";", start);
      db.execute(old.substring(start, end));
    }
    String sql =
        Files.readString(
            Path.of(
                "../shop-app/src/main/resources/db/migration/V649__entity_plan_offline_purchase.sql"));
    for (String s : sql.replaceAll("(?m)^--.*$", "").split(";")) if (!s.isBlank()) db.execute(s);
    String creatorSql =
        Files.readString(
            Path.of(
                "../shop-app/src/main/resources/db/migration/V650__entity_plan_purchase_creator.sql"));
    for (String s : creatorSql.replaceAll("(?m)^--.*$", "").split(";"))
      if (!s.isBlank()) db.execute(s);
    db.execute("ALTER TABLE mch_account ADD COLUMN display_name varchar(100) DEFAULT 'Owner'");
    db.execute("ALTER TABLE mch_store ADD COLUMN name varchar(100) DEFAULT 'Store'");
    db.update(
        "INSERT INTO mch_entity(tenant_no,entity_no,owner_user_no,market,deleted)"
            + " VALUES('MAIN','E1','U1','TW',0),('MAIN','E2','U2','TW',0)");
    db.update(
        "INSERT INTO"
            + " mch_store(tenant_no,store_no,entity_no,status,plan_suspended,deleted,version,updated_at)"
            + " VALUES('MAIN','S1','E1','ACTIVE',0,0,0,NOW()),('MAIN','S2','E1','READONLY',1,0,0,NOW()),('MAIN','S3','E1','READONLY',0,0,0,NOW())");
    db.update(
        "INSERT INTO"
            + " sys_merchant_plan_def(plan_code,name,store_quota,staff_quota,cross_store_stats,enabled,created_at,updated_at)"
            + " VALUES('PRO','成长',10,10,1,1,NOW(),NOW()) ON DUPLICATE KEY UPDATE"
            + " store_quota=10,staff_quota=10");
    db.update(
        "INSERT INTO"
            + " mch_entity_plan(entity_no,plan_code,store_quota,staff_quota,status,granted_by,trial_used,store_quota_override,created_at,updated_at)"
            + " VALUES('E1','FREE',3,0,'ACTIVE','SELF_PAID',1,18,NOW(),NOW())");
    PlatformPaymentPort payments =
        new PlatformPaymentPort() {
          public PaymentOutcome create(PaymentIntentCommand c) {
            return new PaymentOutcome(
                c.orderNo(),
                Optional.of("PAY1"),
                c.platformMerchantNo(),
                c.amount(),
                AsyncStatus.PENDING,
                Optional.empty(),
                Optional.empty());
          }

          public PaymentOutcome query(PaymentQuery q) {
            return closed ? close(q) : create(q.original());
          }

          public PaymentOutcome close(PaymentQuery q) {
            var c = q.original();
            return new PaymentOutcome(
                c.orderNo(),
                Optional.of("PAY1"),
                c.platformMerchantNo(),
                c.amount(),
                AsyncStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                true,
                Optional.of("FENCE1"));
          }

          public PaymentRefundOutcome refund(PaymentRefundCommand c) {
            throw new UnsupportedOperationException();
          }

          public PaymentRefundOutcome queryRefund(PaymentRefundQuery c) {
            throw new UnsupportedOperationException();
          }
        };
    OfflineCollectionPort money =
        new OfflineCollectionPort() {
          public void reconcile(Reconciliation r) {
            closed = true;
          }

          public Optional<OfflineCollectionTerms> terms(String t, String p, String c) {
            return Optional.of(
                new OfflineCollectionTerms(
                    "P",
                    1,
                    "PM1",
                    "TWD",
                    "A1",
                    "v1",
                    86400000,
                    Map.of(
                        "bankCode", "TEST", "bankAccount", "LOCAL-ONLY", "accountName", "本地测试")));
          }

          public ManualResult confirm(ManualPayment c) {
            assertFalse(
                org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            assertEquals(now, c.confirmedAt());
            if (cut) throw new IllegalStateException("cut");
            if (refusal != null) throw new IllegalArgumentException(refusal);
            receipts++;
            return new ManualResult(closed ? "LATE" : mismatch ? "UNMATCHED" : "MATCHED", "PAY1");
          }
        };
    PrivateEvidencePort media =
        new PrivateEvidencePort() {
          public boolean stored(Scope s, String k) {
            return files.contains(k);
          }

          public String signedRead(Scope s, String k) {
            return "private:" + k;
          }

          public void store(Scope s, String k, Upload u) {
            files.add(k);
          }
        };
    StoreOwnershipWritePort ownership =
        new StoreOwnershipWritePort() {
          public void lockEntityAndCheck(String t, String e) {
            db.queryForList(
                "SELECT entity_no FROM mch_entity WHERE tenant_no=? AND entity_no=? FOR UPDATE",
                t,
                e);
          }

          public void lockAndCheck(String t, String e, String s) {
            lockEntityAndCheck(t, e);
          }
        };
    svc =
        new EntityPlanPurchaseServiceImpl(
            db,
            new DataSourceTransactionManager(ds),
            new ObjectMapper(),
            payments,
            money,
            media,
            (t, c) -> Optional.of(new MoneyPolicy("twd", "TWD", 2, 100, 100)),
            ownership,
            e -> e.equals("E1"),
            new Clock() {
              public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
              }

              public Clock withZone(java.time.ZoneId z) {
                return this;
              }

              public Instant instant() {
                return Instant.ofEpochMilli(currentTime);
              }
            },
            "P");
    svc.saveOffer(ops, new OfferEdit("PRO30", 0, "PRO", "成长月包", 30, new Money("TWD", 19900), true));
  }

  @AfterEach
  void clean() {
    if (schema != null) admin.execute("DROP DATABASE " + schema);
  }

  String purchase() {
    return svc.buy(buyer, new Buy("REQ1", "PRO30", 1)).orderNo();
  }

  String evidence(Actor a, String order) {
    String k = svc.reserveEvidence(a, order);
    files.add(k);
    return k;
  }

  void claim(String order) {
    svc.submit(
        buyer,
        order,
        new Claim(
            "C1", new Money("TWD", 19900), now.minusSeconds(60), "BANK1", evidence(buyer, order)));
  }

  Confirm confirm(String order) {
    return new Confirm(
        "A1",
        "C1",
        new Money("TWD", 19900),
        now.minusSeconds(60),
        "BANK1",
        evidence(ops, order),
        "已核对银行流水");
  }

  @Test void directAssistedReceiptCanBeCorrectedAfterDefiniteBankRefusal(){var order=svc.assistedBuy(ops,new AssistedBuy("DIRECT-REFUSED","PRO30",1,"S1","MA1"));var c=new Confirm("DIRECT-A1",null,order.offer().price(),now,"USED-REFERENCE",evidence(ops,order.orderNo()),"bank checked");refusal="OFFLINE_BANK_REFERENCE_CONFLICT";assertEquals("REJECTED",svc.confirm(ops,order.orderNo(),c).status());assertEquals("AWAITING_PAYMENT",svc.order(ops,order.orderNo()).status());refusal=null;assertEquals("APPLIED",svc.confirm(ops,order.orderNo(),new Confirm("DIRECT-A2",null,c.amount(),c.paidAt(),"CORRECTED-REFERENCE",c.evidenceKey(),c.reason())).status());}

  @Test
  void repeatedRejectedLateReceiptPreservesOriginalClosure() {
    String order = purchase();
    svc.cancel(buyer, order);
    svc.voidOrder(ops, order, new VoidCommand("CLOSE", "BANK-CHECK", evidence(ops, order), "bank checked"));
    String closure = db.queryForObject("SELECT closure_json FROM mch_plan_purchase", String.class);
    String proof = evidence(ops, order);
    refusal = "OFFLINE_BANK_REFERENCE_CONFLICT";
    for (int i = 0; i < 2; i++) {
      assertEquals("REJECTED", svc.confirm(ops, order, new Confirm("LATE-" + i, null,
          new Money("TWD", 19900), now, "USED-" + i, proof, "late receipt")).status());
      assertEquals("CANCELLED", svc.order(ops, order).status());
      assertEquals(closure, db.queryForObject("SELECT closure_json FROM mch_plan_purchase", String.class));
    }
    refusal = null;
    assertEquals("REVIEW_REQUIRED", svc.confirm(ops, order, new Confirm("LATE-OK", null,
        new Money("TWD", 19900), now, "CORRECTED-LATE", proof, "late receipt")).status());
  }

  @Test
  void creatorMigrationPreservesExistingMerchantRequestAndSnapshot() throws Exception {
    var order = svc.buy(buyer, new Buy("OLD_REQUEST", "PRO30", 1));
    String before = db.queryForObject("SELECT request_json FROM mch_plan_purchase", String.class);
    db.execute(
        "ALTER TABLE mch_plan_purchase DROP INDEX uk_plan_request, DROP COLUMN creator_no, DROP"
            + " COLUMN creator_kind, ADD UNIQUE KEY"
            + " uk_plan_request(tenant_no,buyer_kind,buyer_no,request_no)");
    db.update(
        "UPDATE mch_plan_purchase SET"
            + " snapshot_json=JSON_REMOVE(snapshot_json,'$.creatorNo','$.creatorKind')");
    String sql =
        Files.readString(
            Path.of(
                "../shop-app/src/main/resources/db/migration/V650__entity_plan_purchase_creator.sql"));
    for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";"))
      if (!statement.isBlank()) db.execute(statement);
    assertEquals(
        before, db.queryForObject("SELECT request_json FROM mch_plan_purchase", String.class));
    assertEquals(order.orderNo(), svc.buy(buyer, new Buy("OLD_REQUEST", "PRO30", 1)).orderNo());
    assertEquals("U1", db.queryForObject("SELECT creator_no FROM mch_plan_purchase", String.class));
    assertEquals(
        "USR", db.queryForObject("SELECT creator_kind FROM mch_plan_purchase", String.class));
  }

  @Test
  void assistanceContextExposesOnlyScopedActiveOwnerAndStore() {
    var context = svc.assistanceContext(ops);
    assertEquals(List.of(new AssistanceOwner("MA1", "Owner", "USR")), context.owners());
    assertEquals(List.of(new AssistanceStore("S1", "Store")), context.stores());
    assertThrows(RuntimeException.class, () -> svc.assistanceContext(buyer));
    assertThrows(
        RuntimeException.class, () -> svc.assistanceContext(new Actor("OP1", "E2", null, true)));
  }

  @Test
  void assistedPurchaseIsPendingInSelectedOwnersHistoryWithFrozenCreator() {
    var command = new AssistedBuy("ASSIST1", "PRO30", 1, "S1", "MA1");
    var order = svc.assistedBuy(ops, command);
    assertEquals("AWAITING_PAYMENT", order.status());
    assertEquals(order.orderNo(), svc.orders(buyer).getFirst().orderNo());
    assertEquals(order.orderNo(), svc.assistedBuy(ops, command).orderNo());
    assertEquals(0, receipts);
    assertEquals("FREE", db.queryForObject("SELECT plan_code FROM mch_entity_plan", String.class));
    var snapshot =
        new ObjectMapper()
            .readValue(
                db.queryForObject("SELECT snapshot_json FROM mch_plan_purchase", String.class),
                EntityPlanPurchaseServiceImpl.Snapshot.class);
    assertEquals("OP1", snapshot.creatorNo());
    assertEquals("OPS", snapshot.creatorKind());
    assertEquals("MA1", snapshot.ownerAccountNo());
    assertEquals("USR", snapshot.buyerKind());
    assertEquals("U1", snapshot.buyerNo());
    assertEquals("E1", snapshot.entityNo());
    assertEquals(new Money("TWD", 19900), snapshot.intent().amount());
    assertEquals("P", order.instructions().profileNo());
    svc.saveOffer(
        ops, new OfferEdit("PRO30", 1, "PRO", "updated", 30, new Money("TWD", 29900), false));
    var replay = svc.assistedBuy(ops, command);
    assertEquals(order.orderNo(), replay.orderNo());
    assertEquals(1, replay.offer().version());
    assertEquals(new Money("TWD", 19900), replay.offer().price());
    assertEquals(order.instructions(), replay.instructions());
  }

  @Test
  void assistedPurchaseRejectsForeignStoreDisabledOwnerAndStaff() {
    assertThrows(
        RuntimeException.class,
        () -> svc.assistedBuy(ops, new AssistedBuy("A", "PRO30", 1, "S2", "MA1")));
    assertThrows(
        RuntimeException.class,
        () -> svc.assistedBuy(ops, new AssistedBuy("A", "PRO30", 1, "S1", "MA2")));
    assertThrows(
        RuntimeException.class,
        () -> svc.assistedBuy(ops, new AssistedBuy("A", "PRO30", 1, "S1", "U1")));
    db.update("UPDATE mch_store SET entity_no='E2' WHERE store_no='S1'");
    assertThrows(
        RuntimeException.class,
        () -> svc.assistedBuy(ops, new AssistedBuy("A", "PRO30", 1, "S1", "MA1")));
    db.update("UPDATE mch_store SET entity_no='E1' WHERE store_no='S1'");
    db.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no='MA1'");
    assertThrows(
        RuntimeException.class,
        () -> svc.assistedBuy(ops, new AssistedBuy("A", "PRO30", 1, "S1", "MA1")));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM mch_plan_purchase", Integer.class));
  }

  @Test
  void creatorAuthoritySeparatesMerchantAndOpsRequestsAndBindsOriginalOwner() {
    var sameNumberOps = new Actor("U1", "E1", null, true);
    var ordinary = svc.buy(buyer, new Buy("SAME", "PRO30", 1));
    db.update("UPDATE mch_plan_purchase SET status='CANCELLED',open_entity=NULL");
    var command = new AssistedBuy("SAME", "PRO30", 1, "S1", "MA1");
    var assisted = svc.assistedBuy(sameNumberOps, command);
    assertNotEquals(ordinary.orderNo(), assisted.orderNo());
    assertEquals(2, svc.orders(buyer).size());
    assertThrows(
        RuntimeException.class,
        () -> svc.assistedBuy(sameNumberOps, new AssistedBuy("SAME", "PRO30", 1, "S2", "MA1")));
    db.update("UPDATE mch_account SET user_no=NULL WHERE mch_account_no='MA1'");
    assertThrows(RuntimeException.class, () -> svc.assistedBuy(sameNumberOps, command));
    db.update("UPDATE mch_account SET user_no='CHANGED' WHERE mch_account_no='MA1'");
    assertThrows(RuntimeException.class, () -> svc.assistedBuy(sameNumberOps, command));
  }

  @Test
  void unlinkedOwnerUsesMchSubjectWithoutBorrowingSameRawUsr() {
    db.update("UPDATE mch_account SET user_no=NULL WHERE mch_account_no='MA1'");
    var order = svc.assistedBuy(ops, new AssistedBuy("MCH1", "PRO30", 1, "S1", "MA1"));
    assertEquals(
        order.orderNo(),
        svc.orders(new Actor("MA1", "E1", "S1", false, "MCH")).getFirst().orderNo());
    assertThrows(
        RuntimeException.class,
        () -> svc.order(new Actor("MA1", "E1", "S1", false, "USR"), order.orderNo()));
  }

  @Test
  void disablingTierHidesItsOfferAndOperatorCanStopItsExistingOffer() {
    db.update("UPDATE sys_merchant_plan_def SET enabled=0 WHERE plan_code='PRO'");
    assertTrue(svc.offers(buyer).isEmpty());
    assertFalse(
        svc.saveOffer(
                ops, new OfferEdit("PRO30", 1, "PRO", "stop", 30, new Money("TWD", 19900), false))
            .enabled());
  }

  @Test
  void canonicalOwnerMembershipWinsOverLegacyOwnerColumn() {
    db.update("UPDATE mch_entity SET owner_user_no='LEGACY-OTHER' WHERE entity_no='E1'");
    String no = purchase();
    claim(no);
    assertEquals("APPLIED", svc.confirm(ops, no, confirm(no)).status());
  }

  @Test
  void mchStaffIdentifierCannotBorrowUsrOwnerWithSameRawNumber() {
    assertThrows(
        RuntimeException.class, () -> svc.offers(new Actor("U1", "E1", "S1", false, "MCH")));
  }

  @Test
  void revokingOriginalOwnerAfterCashAcceptanceRequiresManualDisposition() {
    String no = purchase();
    claim(no);
    var c = confirm(no);
    cut = true;
    assertThrows(IllegalStateException.class, () -> svc.confirm(ops, no, c));
    db.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no='MA1'");
    cut = false;
    assertEquals(Optional.of(true), svc.recoverPayment("MAIN", no, "PAY1"));
    assertEquals(
        "REVIEW_REQUIRED", db.queryForObject("SELECT status FROM mch_plan_purchase", String.class));
  }

  @Test
  void merchantDeclarationCannotGrantAndOperatorAppliesFrozenSnapshotExactlyOnce() {
    String order = purchase();
    claim(order);
    assertEquals("FREE", db.queryForObject("SELECT plan_code FROM mch_entity_plan", String.class));
    db.update("UPDATE sys_merchant_plan_def SET store_quota=1");
    var cmd = confirm(order);
    assertEquals("APPLIED", svc.confirm(ops, order, cmd).status());
    assertEquals("APPLIED", svc.confirm(ops, order, cmd).status());
    assertEquals(1, receipts);
    var row = db.queryForMap("SELECT * FROM mch_entity_plan");
    assertEquals("PRO", row.get("plan_code"));
    assertEquals(10, ((Number) row.get("store_quota")).intValue());
    assertEquals(18, ((Number) row.get("store_quota_override")).intValue());
    assertEquals(1, ((Number) row.get("trial_used")).intValue());
    assertEquals("SELF_PAID", row.get("granted_by"));
    assertEquals(
        "ACTIVE",
        db.queryForObject("SELECT status FROM mch_store WHERE store_no='S2'", String.class));
    assertEquals(
        "READONLY",
        db.queryForObject("SELECT status FROM mch_store WHERE store_no='S3'", String.class));
  }

  @Test
  void acceptedMoneyFailureRecoversOriginalCommand() {
    String order = purchase();
    claim(order);
    var c = confirm(order);
    cut = true;
    assertThrows(IllegalStateException.class, () -> svc.confirm(ops, order, c));
    assertEquals("CONFIRMING", svc.order(buyer, order).status());
    cut = false;
    assertEquals("APPLIED", svc.recover(ops, order).status());
    assertEquals("PRO", db.queryForObject("SELECT plan_code FROM mch_entity_plan", String.class));
  }

  @Test
  void mismatchRecordsMoneyWithoutGrantAndDuplicateClaimPayloadConflicts() {
    String order = purchase();
    claim(order);
    mismatch = true;
    assertEquals("UNMATCHED", svc.confirm(ops, order, confirm(order)).status());
    assertEquals("FREE", db.queryForObject("SELECT plan_code FROM mch_entity_plan", String.class));
    assertThrows(
        RuntimeException.class,
        () ->
            svc.submit(
                buyer,
                order,
                new Claim(
                    "C1",
                    new Money("TWD", 20000),
                    now.minusSeconds(60),
                    "OTHER",
                    evidence(buyer, order))));
  }

  @Test
  void changedSubscriptionAfterOrderDoesNotOverwriteNewGrant() {
    String order = purchase();
    claim(order);
    db.update(
        "UPDATE mch_entity_plan SET plan_code='CHAIN',expire_at=?",
        now.plusSeconds(1000).toEpochMilli());
    assertEquals("REVIEW_REQUIRED", svc.confirm(ops, order, confirm(order)).status());
    assertEquals("CHAIN", db.queryForObject("SELECT plan_code FROM mch_entity_plan", String.class));
    assertEquals(1, receipts);
  }

  @Test
  void definitiveLedgerRefusalAllowsCorrectedOperatorProof() {
    String order = purchase();
    claim(order);
    var c = confirm(order);
    refusal = "OFFLINE_BANK_REFERENCE_CONFLICT";
    assertEquals("REJECTED", svc.confirm(ops, order, c).status());
    assertEquals("SUBMITTED", svc.order(buyer, order).status());
    refusal = null;
    assertEquals(
        "APPLIED",
        svc.confirm(
                ops,
                order,
                new Confirm(
                    "A2",
                    c.claimNo(),
                    c.amount(),
                    c.paidAt(),
                    "NEWBANK",
                    c.evidenceKey(),
                    c.reason()))
            .status());
  }

  @Test
  void evidenceMayBeCorrectedAfterDueTimeWithoutInventingReceipt() {
    String order = purchase();
    claim(order);
    svc.decline(ops, order, "C1", "unclear");
    currentTime += 2 * 86400000L;
    svc.submit(
        buyer,
        order,
        new Claim("C2", new Money("TWD", 19900), now, "BANK1", evidence(buyer, order)));
    assertEquals("SUBMITTED", svc.order(buyer, order).status());
    assertEquals(0, receipts);
  }

  @Test
  void deletedIdentityAfterCashAcceptanceRecordsManualDisposition() {
    String order = purchase();
    claim(order);
    var c = confirm(order);
    cut = true;
    assertThrows(IllegalStateException.class, () -> svc.confirm(ops, order, c));
    db.update("UPDATE mch_entity SET deleted=1 WHERE entity_no='E1'");
    cut = false;
    assertEquals(Optional.of(true), svc.recoverPayment("MAIN", order, "PAY1"));
    assertEquals(
        "REVIEW_REQUIRED", db.queryForObject("SELECT status FROM mch_plan_purchase", String.class));
    assertEquals("FREE", db.queryForObject("SELECT plan_code FROM mch_entity_plan", String.class));
  }

  @Test
  void cancellationRequiresOperatorBankReconciliationBeforeClosing() {
    String order = purchase();
    assertEquals("CANCELLATION_REQUESTED", svc.cancel(buyer, order).status());
    assertFalse(closed);
    assertEquals(
        "CANCELLED",
        svc.voidOrder(
                ops, order, new VoidCommand("V1", "BANK-CHECK", evidence(ops, order), "银行确认无入款"))
            .status());
    assertTrue(closed);
  }

  @Test
  void backgroundRecoveryAcknowledgesOnlyMatchingDurableBusinessDisposition() {
    String order = purchase();
    claim(order);
    var c = confirm(order);
    cut = true;
    assertThrows(IllegalStateException.class, () -> svc.confirm(ops, order, c));
    cut = false;
    assertEquals(Optional.of(true), svc.recoverPayment("MAIN", order, "PAY1"));
    assertEquals(Optional.of(false), svc.recoverPayment("MAIN", order, "OTHER"));
    assertEquals(Optional.empty(), svc.recoverPayment("OTHER", order, "PAY1"));
  }

  @Test
  void rejectedClaimCanBeResubmittedAndForeignActorCannotRead() {
    String order = purchase();
    claim(order);
    assertEquals("DECLINED", svc.decline(ops, order, "C1", "凭证不清晰").status());
    assertThrows(
        RuntimeException.class, () -> svc.order(new Actor("U2", "E2", "S1", false), order));
    svc.submit(
        buyer,
        order,
        new Claim(
            "C2", new Money("TWD", 19900), now.minusSeconds(60), "BANK1", evidence(buyer, order)));
    assertEquals("SUBMITTED", svc.order(buyer, order).status());
  }
}
