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
        "shop.outbox.pump.enabled=false"})
@ActiveProfiles("test")
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
