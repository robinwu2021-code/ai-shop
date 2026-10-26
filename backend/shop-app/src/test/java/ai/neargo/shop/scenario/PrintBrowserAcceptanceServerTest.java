package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.print.api.PrintWorkerBudget;
import ai.neargo.shop.print.channel.port.feie.FeieHttpClient;
import ai.neargo.shop.printbridge.PrintFactReadyConsumer;
import ai.neargo.shop.printing.center.export.PrintExportWorker;
import ai.neargo.shop.printing.center.simulation.SimulationRunner;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.order.printing.service.OrderPrintFactService;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService.ConfirmedPayment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Explicit opt-in loopback server for browser acceptance; never an application endpoint or CI hang. */
@EnabledIfEnvironmentVariable(named = "PRINT_BROWSER_ACCEPTANCE", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shop.print.enabled=true", "server.address=127.0.0.1", "shop.job.enabled=false",
        "shop.outbox.pump.enabled=false", "shop.pay.stored-value.enabled=true", "shop.refund.closure.enabled=true"})
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class PrintBrowserAcceptanceServerTest {
    private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"), "print-browser-real");
    private static final String ORDER = "PRINT-BROWSER-ORDER";
    @LocalServerPort int port;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrderPrintFactService facts;
    @Autowired PrintFactReadyConsumer factConsumer;
    @Autowired SimulationRunner simulations;
    @Autowired PrintExportWorker exports;
    @MockitoBean FeieHttpClient vendor;
    @MockitoBean ai.neargo.shop.pay.entitlement.event.PayEntitlementOutboxRelay backgroundPayRelay;

    @DynamicPropertySource
    static void privateArtifacts(DynamicPropertyRegistry properties) {
        properties.add("shop.print.artifacts.root", () -> ROOT.resolve("artifacts").toString());
        properties.add("shop.print.artifacts.signing-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Test
    void serveIsolatedBrowserJourney() throws Exception {
        Files.createDirectories(ROOT);
        Files.setPosixFilePermissions(ROOT, PosixFilePermissions.fromString("rwx------"));
        Path ready = ROOT.resolve("ready.json"), stop = ROOT.resolve("stop");
        Files.deleteIfExists(ready);
        Files.deleteIfExists(stop);
        // Even a user pressing probe in this isolated test cannot contact a printer vendor.
        when(vendor.call(any(), any(), any())).thenReturn(json.readTree("{\"ret\":1,\"msg\":\"BROWSER_TEST_NO_VENDOR\"}"));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String phone = "12600825901";
        String consumer = TestLogin.consumer(mvc, json, otp, phone);
        var application = mvc.perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + consumer).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "打印浏览器隔离验收店", "subject", "INDIVIDUAL_BIZ",
                                "contactName", "软件验收", "contactPhone", phone, "category", "食品",
                                "serviceScope", "COMMUNITY", "communityNos", List.of("CM001")))))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(application).path("data").path("applyNo").asString();
        String operator = TestLogin.admin(mvc, json);
        mvc.perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + operator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        String entity = jdbc.queryForObject("SELECT entity_no FROM mch_entity WHERE name=?", String.class, "打印浏览器隔离验收店");
        String store = jdbc.queryForObject("SELECT store_no FROM mch_store WHERE entity_no=?", String.class, entity);
        String token = TestLogin.merchantOwner(mvc, json, otp, phone);
        jdbc.update("INSERT INTO ord_order(order_no,user_no,pay_amount,goods_amount,status,currency,paid_at,created_at,updated_at)"
                        + " VALUES (?,'PRINT-BROWSER-BUYER',8800,10000,'WAIT_FULFILL','CNY',?,now(),now())",
                ORDER, System.currentTimeMillis());
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,entity_name,store_no,goods_amount,"
                        + "discount_amount,freight_amount,pay_amount,fulfillment,status,created_at,updated_at)"
                        + " VALUES (?,?,'PRINT-BROWSER-BUYER',?,'打印浏览器隔离验收店',?,10000,1200,0,8800,'STORE_PICKUP','WAIT_FULFILL',now(),now())",
                ORDER + "-SUB", ORDER, entity, store);
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,title,qty,amount,price,created_at,updated_at)"
                        + " VALUES (?,?,'PRINT-BROWSER-GOODS','PRINT-BROWSER-SKU','浏览器冻结商品',2,10000,5000,now(),now())",
                ORDER + "-SUB", ORDER);
        new TransactionTemplate(transactions).execute(status -> facts.recordPaid(ORDER, new ConfirmedPayment("WECHAT", "PRINT-BROWSER-PAY")));
        var outbox = jdbc.queryForMap("SELECT * FROM sys_outbox WHERE event_type='ORDER_PRINT_FACT_READY' AND aggregate_id=?", ORDER);
        var event = new SysOutbox();
        event.setEventType("ORDER_PRINT_FACT_READY");
        event.setAggregateId(ORDER);
        event.setPayload(outbox.get("payload").toString());
        factConsumer.consume(event);
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM ord_print_fact WHERE order_no=?", Long.class, ORDER));
        var manifest = new java.util.LinkedHashMap<String, String>(Map.of("mode", "REAL_ISOLATED_H2_HTTP",
                "backendUrl", "http://127.0.0.1:" + port, "token", token, "operatorToken", operator,
                "tenantNo", "MAIN", "entityNo", entity, "storeNo", store, "orderNo", ORDER));
        var other = otherMerchant(mvc, operator);
        manifest.put("otherToken", other.get("token"));
        manifest.put("otherEntityNo", other.get("entityNo"));
        manifest.put("otherStoreNo", other.get("storeNo"));
        manifest.put("deniedToken", restrictedStaff(mvc, token, store));
        prepareBusinessSources(mvc, entity, store, manifest);
        Files.writeString(ready, json.writeValueAsString(manifest));
        Files.setPosixFilePermissions(ready, PosixFilePermissions.fromString("rw-------"));
        Instant deadline = Instant.now().plus(Duration.ofMinutes(20));
        try {
            while (!Files.exists(stop) && Instant.now().isBefore(deadline)) {
                var budget = new PrintWorkerBudget(20, Duration.ofSeconds(3), "browser-artifact-only");
                simulations.runNext(budget);
                exports.generateNext(budget);
                Thread.sleep(250);
            }
        } finally {
            Files.deleteIfExists(ready);
        }
    }
    /** Financial rows are created by their real owners; only test authentication/channel setup is controlled. */
    private void prepareBusinessSources(MockMvc mvc, String entity, String store,
                                        Map<String, String> manifest) throws Exception {
        var plans = context.getBean(ai.neargo.shop.pay.storedvalue.service.StoredValuePlanService.class);
        var recharges = context.getBean(ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService.class);
        var plan = plans.create(new ai.neargo.shop.pay.entitlement.dto.CatalogScope("MAIN", entity, "OWNER", false),
                new ai.neargo.shop.spi.storedvalue.StoredValuePlans.Create(store, "浏览器真实充值", "CNY", 10000, 3000), id());
        var command = new ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Create("PRINT-BROWSER-WALLET-USER",
                store, "打印浏览器隔离验收店", plan.planNo(), plan.versionNo(), "a".repeat(64));
        var first = recharges.confirmOffline(entitlementContext(), entity, command);
        recharges.confirmOffline(entitlementContext(), entity, command); // current balance differs from receipt
        var event = jdbc.queryForObject("SELECT event_no,payload FROM pay_ent_outbox WHERE aggregate_no=?"
                        + " AND event_type='STORED_VALUE_RECEIPT_CONFIRMED'", (rs, row) -> {
            var outbox = new SysOutbox(); outbox.setEventNo(rs.getString(1)); outbox.setPayload(rs.getString(2));
            outbox.setAggregateType("STORED_VALUE_RECHARGE"); outbox.setAggregateId(first.rechargeNo());
            outbox.setEventType("STORED_VALUE_RECEIPT_CONFIRMED"); return outbox;
        }, first.rechargeNo());
        context.getBean(ai.neargo.shop.printbridge.BusinessPrintReceiptConsumer.class).consume(event);
        manifest.put("rechargeNo", first.rechargeNo());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM biz_print_fact WHERE source_no=?", Long.class, first.rechargeNo()));
        // A same-store wallet-only employee exercises real API authorization, not an owner bypass.
        manifest.put("walletToken", sourceStaff(mvc, entity, store, "PRINT-BROWSER-WALLET", "12600825904", false,
                "[\"pay:sv:recharge:read\",\"pay:sv:account:read\",\"biz:print:read\",\"biz:print:simulate\",\"biz:print:export\"]"));
        try (var f = new ai.neargo.shop.support.BasicRefundFixture(context)) {
            String customer = f.login("12600825905");
            var created = f.request(customer, "/mp/order", "{\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":3}],"
                    + "\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"usePoints\":0}");
            assertEquals(0, created.path("code").asInt(), created.toString());
            String payNo = created.path("data").path("payOrderNo").asString(); f.pay(customer, payNo);
            String sub = jdbc.queryForObject("SELECT sub_order_no FROM ord_sub_order WHERE order_no=?", String.class, payNo);
            var purchase = new ai.neargo.shop.support.BasicRefundFixture.Purchase(customer, payNo, sub);
            f.prepare(purchase);
            long item = jdbc.queryForObject("SELECT id FROM ord_item WHERE sub_order_no=?", Long.class, sub);
            var partial = f.request(customer, "/mp/order/" + sub + "/after-sale", "{\"type\":\"REFUND_ONLY\","
                    + "\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\",\"items\":[{\"itemId\":" + item + ",\"qty\":1}]}");
            assertEquals(20011, partial.path("code").asInt(), partial.toString());
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM ord_after_sale WHERE sub_order_no=?", Long.class, sub));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM biz_print_fact WHERE order_no=?", Long.class, payNo));
            manifest.put("partialRefundResult", "REJECTED_20011_NO_AFTERSALE_NO_RECEIPT");
            String no = f.apply(purchase, "REFUND_ONLY"); f.approve(purchase, no);
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM biz_print_fact WHERE source_no=?", Long.class, no));
            f.confirmCash(no);
            context.getBean(ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService.class).recover("RF-" + no);
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM biz_print_fact WHERE source_no=?", Long.class, no));
            String refundStore = jdbc.queryForObject("SELECT store_no FROM ord_sub_order WHERE sub_order_no=?", String.class, sub);
            manifest.put("refundNo", no); manifest.put("refundOrderNo", payNo); manifest.put("refundSubOrderNo", sub);
            manifest.put("refundEntityNo", "M0001"); manifest.put("refundStoreNo", refundStore);
            manifest.put("refundToken", sourceStaff(mvc, "M0001", refundStore, "PRINT-BROWSER-REFUND", "12600825906", true, "[]"));
        }
    }

    private String sourceStaff(MockMvc mvc, String entity, String store, String account,
                               String phone, boolean owner, String permissions) throws Exception {
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,login_phone,is_owner,status,created_at,updated_at)"
                + " VALUES(?,?,?,?,'ACTIVE',now(),now())", account, entity, phone, owner ? 1 : 0);
        String role = owner ? "OWNER" : "PRINT_WALLET_ACCEPTANCE";
        if (!owner) jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,builtin,perms,created_at,updated_at)"
                + " VALUES(?,?,?,0,?,now(),now())", entity, role, "打印钱包专岗验收", permissions);
        jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                + " VALUES(?,?,?,now(),now())", account, store, role);
        return TestLogin.merchantStaff(mvc, json, otp, phone);
    }
    private static String id() { return java.util.UUID.randomUUID().toString().replace("-", ""); }
    private static ai.neargo.shop.spi.entitlement.EntitlementContext entitlementContext() {
        return new ai.neargo.shop.spi.entitlement.EntitlementContext("MAIN", "OWNER", id(), "f".repeat(64));
    }

    private Map<String, String> otherMerchant(MockMvc mvc, String operator) throws Exception {
        String phone = "12600825902", name = "打印浏览器另一商家";
        String consumer = TestLogin.consumer(mvc, json, otp, phone);
        var application = mvc.perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + consumer).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "subject", "INDIVIDUAL_BIZ",
                                "contactName", "隔离验收", "contactPhone", phone, "category", "食品",
                                "serviceScope", "COMMUNITY", "communityNos", List.of("CM001")))))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(application).path("data").path("applyNo").asString();
        mvc.perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + operator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        String entity = jdbc.queryForObject("SELECT entity_no FROM mch_entity WHERE name=?", String.class, name);
        String store = jdbc.queryForObject("SELECT store_no FROM mch_store WHERE entity_no=?", String.class, entity);
        return Map.of("token", TestLogin.merchantOwner(mvc, json, otp, phone), "entityNo", entity, "storeNo", store);
    }

    private String restrictedStaff(MockMvc mvc, String owner, String store) throws Exception {
        String phone = "12600825903";
        var response = mvc.perform(post("/biz/staff").header("Authorization", "Bearer " + owner)
                        .header("X-Store-No", store).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("loginPhone", phone, "displayName", "无打印权配送员"))))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String account = json.readTree(response).path("data").path("mchAccountNo").asString();
        mvc.perform(post("/biz/staff/" + account + "/store").header("Authorization", "Bearer " + owner)
                        .header("X-Store-No", store).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("storeNo", store, "role", "COURIER", "granted", true))))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.merchantStaff(mvc, json, otp, phone);
    }

}
