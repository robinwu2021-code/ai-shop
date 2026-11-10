package ai.neargo.shop.pay.service.impl;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.commercebridge.*;
import ai.neargo.shop.commercebridge.port.ShopMallPaymentRecovery;
import ai.neargo.shop.merchant.plan.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.*;
import ai.neargo.shop.merchant.plan.impl.EntityPlanPurchaseServiceImpl;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real canonical stl_payment + real entity purchase SQL; no gateway or real bank accounts. */
@EnabledIfEnvironmentVariable(named = "COMMERCE_MYSQL_URL", matches = ".+")
class EntityPlanOfflineLedgerMysqlTest {
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
  static class MethodSecurity {}

  private static void identity(ai.neargo.shop.auth.LoginUser user) {
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                user, null, List.of()));
  }

  @org.junit.jupiter.api.AfterEach
  void clear() {
    ai.neargo.shop.auth.BizContext.clear();
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
  }

  @Test
  void merchantSubmissionOperatorReceiptEntitlementsAndMoneyHandoffCloseOnOneLedger()
      throws Exception {
    try (var f = new PlatformOfflineCollectionMysqlTest.Fixture()) {
      var db = f.base.jdbc;
      f.profile(1, "TWD");
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
      String ddl =
          Files.readString(Path.of("src/main/resources/db/migration/V150__merchant_plan.sql"));
      for (String table : List.of("sys_merchant_plan_def", "mch_entity_plan")) {
        int start = ddl.indexOf("CREATE TABLE IF NOT EXISTS " + table);
        db.execute(ddl.substring(start, ddl.indexOf(";", start)));
      }
      ddl =
          Files.readString(
              Path.of("src/main/resources/db/migration/V649__entity_plan_offline_purchase.sql"));
      for (String sql : ddl.replaceAll("(?m)^--.*$", "").split(";"))
        if (!sql.isBlank()) db.execute(sql);
      db.update(
          "INSERT INTO mch_entity(tenant_no,entity_no,owner_user_no,market,deleted)"
              + " VALUES('MAIN','E1','U1','TW',0)");
      db.update("INSERT INTO mch_store VALUES('MAIN','S1','E1','ACTIVE',0,0,0,NOW())");
      db.update(
          "INSERT INTO"
              + " sys_merchant_plan_def(plan_code,name,store_quota,staff_quota,cross_store_stats,enabled,created_at,updated_at)"
              + " VALUES('PRO','Test quota',10,10,1,1,NOW(),NOW()) ON DUPLICATE KEY UPDATE"
              + " store_quota=10,staff_quota=10");
      var tx = new DataSourceTransactionManager(f.base.ds);
      Set<String> files = new HashSet<>();
      PrivateEvidencePort media =
          new PrivateEvidencePort() {
            public boolean stored(Scope s, String k) {
              return files.contains(k);
            }

            public String signedRead(Scope s, String k) {
              return "private:" + k;
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
      Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      var service =
          new EntityPlanPurchaseServiceImpl(
              db,
              tx,
              new ObjectMapper(),
              new ShopMallPlatformPaymentAdapter(
                  f.base.service, null, ShopMallPayerBindings.UNAVAILABLE),
              new ShopMallOfflineCollectionAdapter(f.base.service),
              media,
              (t, c) -> Optional.of(new MoneyPolicy("twd", "TWD", 2, 100, 100)),
              ownership,
              e -> true,
              Clock.fixed(now, ZoneOffset.UTC),
              "profile");
      var owner = new Actor("U1", "E1", "S1", false);
      var ops = new Actor("ops", "E1", null, true);
      service.saveOffer(
          ops, new OfferEdit("P30", 0, "PRO", "Test plan", 30, new Money("TWD", 19900), true));
      // Actual MVC + method security, with controlled local principals rather than real login/SMS.
      var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
      context.getEnvironment().setActiveProfiles("api", "ops");
      org.springframework.test.context.support.TestPropertySourceUtils
          .addInlinedPropertiesToEnvironment(
              context,
              "commerce.enabled=true",
              "commerce.mall.http.enabled=true",
              "commerce.mall.enabled=true",
              "commerce.mall.payment.enabled=true",
              "commerce.mall.offline.enabled=true");
      context.register(MethodSecurity.class);
      context.registerBean(
          "perm",
          ai.neargo.shop.auth.PermChecker.class,
          () ->
              new ai.neargo.shop.auth.PermChecker(
                  context.getBeanProvider(ai.neargo.shop.auth.LivePermResolver.class)));
      context.registerBean(
          ai.neargo.shop.merchant.api.biz.BizPlanPurchaseController.class,
          () -> new ai.neargo.shop.merchant.api.biz.BizPlanPurchaseController(service));
      context.registerBean(
          ai.neargo.shop.merchant.api.ops.OpsPlanPurchaseController.class,
          () ->
              new ai.neargo.shop.merchant.api.ops.OpsPlanPurchaseController(
                  service,
                  org.mockito.Mockito.mock(ai.neargo.shop.spi.platform.AuditLogPort.class)));
      context.refresh();
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  context.getBean(ai.neargo.shop.merchant.api.biz.BizPlanPurchaseController.class),
                  context.getBean(ai.neargo.shop.merchant.api.ops.OpsPlanPurchaseController.class))
              .setControllerAdvice(
                  new ai.neargo.shop.common.ApiResponseWrapper(),
                  new ai.neargo.shop.common.GlobalExceptionHandler())
              .build();
      var json = new ObjectMapper();
      identity(
          new ai.neargo.shop.auth.LoginUser(
              ai.neargo.shop.auth.Realm.MERCHANT,
              ai.neargo.auth.store.SubjectKind.USR,
              "U1",
              "local owner",
              List.of(),
              List.of(),
              "MAIN",
              ai.neargo.common.data.scope.DataScopeSpec.ALL));
      ai.neargo.shop.auth.BizContext.set(
          new ai.neargo.shop.auth.BizContext("E1", Set.of(), Set.of(), Set.of("S1"), "S1", true));
      String response =
          mvc.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                          "/biz/plan/purchases")
                      .header("X-Store-No", "S1")
                      .contentType("application/json")
                      .content(json.writeValueAsString(new Buy("BUY1", "P30", 1))))
              .andExpect(
                  org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                          "$.code")
                      .value(0))
              .andReturn()
              .getResponse()
              .getContentAsString();
      String no = json.readTree(response).path("data").path("orderNo").asString();
      var order = service.order(owner, no);
      assertEquals(
          0,
          db.queryForObject(
              "SELECT COUNT(*) FROM stl_payment WHERE status='SUCCESS'", Integer.class));
      String key = service.reserveEvidence(owner, no);
      files.add(key);
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/biz/plan/purchases/" + no + "/claims")
                  .contentType("application/json")
                  .content(
                      json.writeValueAsString(
                          new Claim("C1", order.offer().price(), now, "BANK-LOCAL-1", key))))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                  .value(0));
      assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM mch_entity_plan", Integer.class));
      String proof = service.reserveEvidence(ops, no);
      files.add(proof);
      var confirm =
          new Confirm(
              "A1",
              "C1",
              order.offer().price(),
              now,
              "BANK-LOCAL-1",
              proof,
              "local fixture verified");
      identity(
          ai.neargo.shop.auth.LoginUser.operator(
              "ops", "local operator", List.of(), List.of(ai.neargo.shop.auth.Perms.MERCHANT_BAN)));
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/ops/merchant-plan-purchases/E1/" + no + "/confirm")
                  .contentType("application/json")
                  .content(json.writeValueAsString(confirm)))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                  .value(10403));
      assertEquals(
          0,
          db.queryForObject(
              "SELECT COUNT(*) FROM stl_payment WHERE status='SUCCESS'", Integer.class));
      identity(
          ai.neargo.shop.auth.LoginUser.operator(
              "ops",
              "local operator",
              List.of(),
              List.of(ai.neargo.shop.auth.Perms.MERCHANT_BAN, "commerce:mall:offline:confirm")));
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/ops/merchant-plan-purchases/E1/" + no + "/confirm")
                  .contentType("application/json")
                  .content(json.writeValueAsString(confirm)))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.status")
                  .value("APPLIED"));
      assertEquals("APPLIED", service.confirm(ops, no, confirm).status());
      context.close();
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM stl_payment WHERE status='SUCCESS' AND direction='PAY'",
              Integer.class));
      assertEquals(
          "PRO",
          db.queryForObject(
              "SELECT plan_code FROM mch_entity_plan WHERE entity_no='E1'", String.class));
      String payment =
          db.queryForObject(
              "SELECT payment_no FROM stl_payment WHERE status='SUCCESS'", String.class);
      var handoffs = new PlatformMoneyHandoffServiceImpl(f.base.ds, tx);
      var recovery =
          new ShopMallPaymentRecovery(f.base.service, null, handoffs, null, null, service);
      assertTrue(recovery.recover(payment));
      assertEquals(
          "DONE",
          db.queryForObject(
              "SELECT platform_handoff_state FROM stl_payment WHERE payment_no=?",
              String.class,
              payment));
      assertEquals(0, f.base.sends.get());
      var next = service.buy(owner, new Buy("BUY2", "P30", 1));
      service.cancel(owner, next.orderNo());
      assertEquals("CANCELLATION_REQUESTED", service.order(owner, next.orderNo()).status());
      proof = service.reserveEvidence(ops, next.orderNo());
      files.add(proof);
      assertEquals(
          "CANCELLED",
          service
              .voidOrder(
                  ops,
                  next.orderNo(),
                  new VoidCommand("V1", "BANK-CHECK-2", proof, "bank reconciliation empty"))
              .status());
      assertEquals(
          "CLOSED",
          f.base
              .service
              .query(
                  new ai.neargo.shop.pay.service.PlatformCollectionService.Key(
                      "MAIN", next.orderNo(), next.orderNo()))
              .state());
      Long originalExpiry =
          db.queryForObject(
              "SELECT expire_at FROM mch_entity_plan WHERE entity_no='E1'", Long.class);
      var another = service.buy(owner, new Buy("BUY3", "P30", 1));
      String lateProof = service.reserveEvidence(owner, next.orderNo());
      files.add(lateProof);
      service.submit(
          owner,
          next.orderNo(),
          new Claim("C-LATE", next.offer().price(), now, "LATE-BANK", lateProof));
      lateProof = service.reserveEvidence(ops, next.orderNo());
      files.add(lateProof);
      assertEquals(
          "REVIEW_REQUIRED",
          service
              .confirm(
                  ops,
                  next.orderNo(),
                  new Confirm(
                      "A-LATE",
                      "C-LATE",
                      next.offer().price(),
                      now,
                      "LATE-BANK",
                      lateProof,
                      "real transfer discovered after cancellation"))
              .status());
      assertEquals(
          originalExpiry,
          db.queryForObject(
              "SELECT expire_at FROM mch_entity_plan WHERE entity_no='E1'", Long.class));
      assertEquals(
          "E1",
          db.queryForObject(
              "SELECT open_entity FROM mch_plan_purchase WHERE order_no=?",
              String.class,
              another.orderNo()));
      String latePayment =
          db.queryForObject(
              "SELECT payment_no FROM mch_plan_purchase WHERE order_no=?",
              String.class,
              next.orderNo());
      assertTrue(recovery.recover(latePayment));
      assertEquals(
          "DONE",
          db.queryForObject(
              "SELECT platform_handoff_state FROM stl_payment WHERE payment_no=?",
              String.class,
              latePayment));
    }
  }
}
