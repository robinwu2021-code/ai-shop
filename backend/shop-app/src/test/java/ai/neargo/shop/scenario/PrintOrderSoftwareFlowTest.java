package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.print.api.PrintFactRef;
import ai.neargo.shop.print.api.FrozenJson;
import ai.neargo.shop.print.api.PrintBatchResolved;
import ai.neargo.shop.print.api.PrintHashes;
import ai.neargo.shop.print.api.PrintScope;
import ai.neargo.shop.print.api.PrintWorkerBudget;
import ai.neargo.shop.print.export.PrintExportWorker;
import ai.neargo.shop.print.simulation.SimulationRunner;
import ai.neargo.shop.printbridge.PrintFactReadyConsumer;
import ai.neargo.shop.printbridge.PrintNotifyBridge;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.order.printing.service.OrderPrintFactService;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService.ConfirmedPayment;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the application boundary with real source persistence, HTTP security and private PDF storage. */
@SpringBootTest(properties = "shop.print.enabled=true")
@ActiveProfiles("test")
class PrintOrderSoftwareFlowTest {
    @TempDir static Path artifacts;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OrderPrintFactService facts;
    @Autowired PrintFactReadyConsumer factConsumer;
    @Autowired SimulationRunner simulations;
    @Autowired PrintNotifyBridge notifications;
    @Autowired PrintExportWorker exports;
    MockMvc mvc;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("shop.print.artifacts.root", () -> artifacts.toString());
        properties.add("shop.print.artifacts.signing-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @BeforeEach
    void client() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void paidFactSurvivesRedeliveryAndMerchantCanSimulateAndDownloadItsFrozenReceipt() throws Exception {
        var owner = merchant("12600825101", "打印软件验收店");
        String order = "PRINT-SOFTWARE-ORDER";
        seedPaidOrder(order, owner);
        var tx = new TransactionTemplate(transactions);
        PrintFactRef ref = tx.execute(s -> facts.recordPaid(order, new ConfirmedPayment("WECHAT", "PAY-SOFT")))
                .getFirst();
        String frozen = facts.snapshot(ref).immutablePayload().canonicalJson();
        assertEquals("READY", facts.snapshot(ref).printability().name());
        var repeated = tx.execute(s -> facts.recordPaid(order, new ConfirmedPayment("WECHAT", "PAY-SOFT")));
        assertEquals(List.of(ref), repeated);
        assertEquals(1L, count("ord_print_fact", "order_no", order));
        var event = jdbc.queryForMap("select * from sys_outbox where event_type='ORDER_PRINT_FACT_READY' "
                + "and aggregate_id=?", order);
        var row = new SysOutbox();
        row.setEventType("ORDER_PRINT_FACT_READY");
        row.setAggregateId(order);
        row.setPayload(event.get("payload").toString());
        factConsumer.consume(row);
        factConsumer.consume(row);
        // Disabled automatic policy leaves a single skipped request; replays do not backfill old payments.
        assertEquals(1L, count("prn_request", "order_no", order));
        assertEquals(0L, count("prn_job", "store_no", owner.store()));
        jdbc.update("update ord_item set title='已修改的商品名',amount=1 where order_no=?", order);
        assertEquals(frozen, facts.snapshot(ref).immutablePayload().canonicalJson());

        var summary = data(get("/biz/print/orders/" + order + "/summary").param("storeNo", owner.store()), owner);
        assertTrue(summary.toString().contains(ref.factNo()), summary.toString());
        long before = count("prn_job", "store_no", owner.store());
        String body = json.writeValueAsString(Map.of(
                "storeNo", owner.store(), "scene", "ORDER_PAID", "variantCode", "STANDARD_58",
                "fixtureCode", "SUCCESS", "source", Map.of("type", "FACT", "factRef", ref),
                "templateSelection", Map.of("mode", "SCENE_DEFAULT", "variantCode", "STANDARD_58"),
                "outputOptions", Map.of("pdfEnabled", false, "pdfProfile", "ORIGINAL_ROLL")));
        var operation = data(post("/biz/print/simulations").header("Idempotency-Key", "software-simulation")
                .contentType(MediaType.APPLICATION_JSON).content(body), owner);
        var replay = data(post("/biz/print/simulations").header("Idempotency-Key", "software-simulation")
                .contentType(MediaType.APPLICATION_JSON).content(body), owner);
        assertEquals(operation, replay);
        String simulation = operation.path("targetNo").asText();
        var budget = new PrintWorkerBudget(20, Duration.ofSeconds(30), "software-acceptance");
        assertEquals(1, simulations.runNext(budget).succeeded());
        var view = data(get("/biz/print/simulations/" + simulation), owner);
        assertEquals("PASSED", view.path("status").asText());
        assertEquals(before, count("prn_job", "store_no", owner.store()));
        var export = data(post("/biz/print/exports").header("Idempotency-Key", "software-pdf")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "storeNo", owner.store(), "profile", "ORIGINAL_ROLL",
                        "source", Map.of("type", "SIMULATION", "no", simulation,
                                "expectedRevision", view.path("revision").asLong())))), owner);
        assertEquals(1, exports.generateNext(budget).succeeded());
        String exportNo = export.path("targetNo").asText();
        assertEquals("READY", data(get("/biz/print/exports/" + exportNo), owner).path("status").asText());
        var grant = data(post("/biz/print/exports/" + exportNo + "/download-grants"), owner);
        String url = grant.path("url").asText();
        var response = mvc.perform(get(URI.create(url)).secure(true)).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("application/pdf", response.getContentType());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        byte[] pdf = response.getContentAsByteArray();
        assertTrue(pdf.length > 1000);
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("冻结商品"), text);
            assertTrue(text.contains("88.00"), text);
            assertTrue(text.contains(order), text);
            assertFalse(text.contains("已修改的商品名"), text);
        }
        var denied = mvc.perform(get(URI.create(url.replace("signature=", "signature=tampered"))).secure(true))
                .andReturn().getResponse();
        assertEquals(422, denied.getStatus());
        assertEquals("print.downloadExpired", json.readTree(denied.getContentAsString())
                .path("data").path("key").asText());
        assertEquals(before, count("prn_job", "store_no", owner.store()));

        assertEquals(0L, count("sys_outbox", "event_type", "PRINT_BATCH_RESOLVED"),
                "Simulation and PDF must not emit physical-print completion events");
        // Exercise delivery of an authoritative completion event; the event itself is a fixture here.
        String notificationPolicy = json.writeValueAsString(Map.of("onBatchResolved", true,
                "channels", List.of("IN_APP"), "recipientRoleKeys", List.of("OWNER")));
        var completion = new PrintBatchResolved(new PrintScope(ref.tenantNo(), owner.entity(), owner.store()),
                "PRINT-NOTIFY-BATCH", "RESOLVED", Instant.EPOCH, "INITIAL",
                new FrozenJson(notificationPolicy, PrintHashes.sha256(notificationPolicy)));
        var notificationEvent = new SysOutbox();
        notificationEvent.setEventNo("PRINT-SOFTWARE-NOTIFICATION");
        notificationEvent.setEventType(completion.eventType());
        notificationEvent.setPayload(json.writeValueAsString(completion));
        notifications.consume(notificationEvent);
        notifications.consume(notificationEvent);
        String ownerUser = jdbc.queryForObject("select owner_user_no from mch_entity where entity_no=?",
                String.class, owner.entity());
        assertEquals(1L, count("notify_message", "dedup_key",
                "PRINT:" + PrintHashes.sha256(notificationEvent.getEventNo() + ":" + ownerUser)));

        var other = merchant("12600825102", "打印隔离验收店");
        var forbidden = mvc.perform(get("/biz/print/exports/" + exportNo)
                .header("Authorization", "Bearer " + other.token()).header("X-Store-No", other.store()))
                .andReturn().getResponse().getContentAsString();
        assertNotEquals(0, json.readTree(forbidden).path("code").asInt());
        var noGrant = mvc.perform(post("/biz/print/exports/" + exportNo + "/download-grants")
                .header("Authorization", "Bearer " + other.token()).header("X-Store-No", other.store()))
                .andReturn().getResponse().getContentAsString();
        assertNotEquals(0, json.readTree(noGrant).path("code").asInt());
        var foreignSource = (ObjectNode) json.readTree(body);
        foreignSource.put("storeNo", other.store());
        mvc.perform(post("/biz/print/simulations").header("Authorization", "Bearer " + other.token())
                        .header("X-Store-No", other.store()).header("Idempotency-Key", "foreign-fact")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(foreignSource)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data.key").value("print.forbidden"));

        // This employee belongs to the same store: only the action permission should deny these requests.
        String phone = "12600825103";
        jdbc.update("insert into mch_account(mch_account_no,entity_no,login_phone,status,created_at,updated_at) "
                + "values('PRINT-COURIER',?,?,'ACTIVE',now(),now())", owner.entity(), phone);
        jdbc.update("insert into mch_store_role(mch_account_no,store_no,role,created_at,updated_at) "
                + "values('PRINT-COURIER',?,'COURIER',now(),now())", owner.store());
        String courier = TestLogin.merchantStaff(mvc, json, otp, phone);
        mvc.perform(get("/biz/print/exports/" + exportNo).header("Authorization", "Bearer " + courier)
                        .header("X-Store-No", owner.store()))
                .andExpect(jsonPath("$.code").value(70006));
        mvc.perform(post("/biz/print/exports/" + exportNo + "/download-grants")
                        .header("Authorization", "Bearer " + courier).header("X-Store-No", owner.store()))
                .andExpect(jsonPath("$.code").value(70006));
        mvc.perform(post("/biz/print/simulations").header("Authorization", "Bearer " + courier)
                        .header("X-Store-No", owner.store()).header("Idempotency-Key", "courier-simulation")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(70006));
    }

    @Test
    void rolledBackSourceTransactionDoesNotLeavePrintFactsOrReadyEvents() {
        var owner = new Owner("", "PRINT-ROLLBACK-ENTITY", "PRINT-ROLLBACK-STORE");
        String order = "PRINT-ROLLBACK-ORDER";
        seedPaidOrder(order, owner);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            facts.recordPaid(order, new ConfirmedPayment("WECHAT", "PAY-ROLLBACK"));
            assertEquals(1L, count("ord_print_fact", "order_no", order));
            s.setRollbackOnly();
        });
        assertEquals(0L, count("ord_print_fact", "order_no", order));
        assertEquals(0L, count("sys_outbox", "aggregate_id", order));
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Owner owner) throws Exception {
        String body = mvc.perform(request.header("Authorization", "Bearer " + owner.token())
                        .header("X-Store-No", owner.store()))
                .andExpect(result -> assertEquals(200, result.getResponse().getStatus(),
                        result.getResponse().getContentAsString())).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private Owner merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc, json, otp, phone);
        String application = mvc.perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "name", name, "subject", "INDIVIDUAL_BIZ", "contactName", "打印验收",
                                "contactPhone", phone, "category", "食品", "serviceScope", "COMMUNITY",
                                "communityNos", List.of("CM001")))))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(application).path("data").path("applyNo").asText();
        mvc.perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + TestLogin.operator(mvc, json, "bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        String entity = jdbc.queryForObject("select entity_no from mch_entity where name=?", String.class, name);
        String store = jdbc.queryForObject("select store_no from mch_store where entity_no=?", String.class, entity);
        return new Owner(TestLogin.merchantOwner(mvc, json, otp, phone), entity, store);
    }

    private void seedPaidOrder(String order, Owner owner) {
        jdbc.update("insert into ord_order(order_no,user_no,pay_amount,goods_amount,status,currency,paid_at,"
                + "created_at,updated_at) values(?,'PRINT-BUYER',8800,10000,'WAIT_FULFILL','CNY',?,now(),now())",
                order, System.currentTimeMillis());
        jdbc.update("insert into ord_sub_order(sub_order_no,order_no,user_no,entity_no,entity_name,store_no,"
                + "goods_amount,discount_amount,freight_amount,pay_amount,fulfillment,status,created_at,updated_at) "
                + "values(?,?,'PRINT-BUYER',?,'打印软件验收店',?,10000,1200,0,8800,'STORE_PICKUP',"
                + "'WAIT_FULFILL',now(),now())", order + "-SUB", order, owner.entity(), owner.store());
        jdbc.update("insert into ord_item(sub_order_no,order_no,goods_no,sku_no,title,qty,amount,price,"
                + "created_at,updated_at) values(?,?,'PRINT-GOODS','PRINT-SKU','冻结商品',2,10000,5000,now(),now())",
                order + "-SUB", order);
    }

    private long count(String table, String column, String value) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Long.class, value);
    }

    private record Owner(String token, String entity, String store) {}
}
