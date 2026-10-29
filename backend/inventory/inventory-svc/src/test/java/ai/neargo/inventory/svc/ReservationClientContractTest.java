package ai.neargo.inventory.svc;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.client.CircuitBreaker;
import ai.neargo.inventory.client.InventoryConsole;
import ai.neargo.inventory.client.InventoryConsoleApi;
import ai.neargo.inventory.client.InventoryReservationApi;
import ai.neargo.inventory.domain.dto.InventoryVOs;
import tools.jackson.core.type.TypeReference;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.inventory.client.InventoryStockApi;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商城切流的客户端对真实控制器（M8 T8.3）：系统凭证 + 业主头、按 SKU 号 / 门店号预留、错误码原样回到调用方、
 * 没翻到独立服务的业主被拒。传输走 MockMvc，客户端的错误解码与熔断是真的。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_client;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=SHOP",
        "inventory.instance.allowed-systems=SHOP",
        "inventory.admin.token=admin-test-token"})
class ReservationClientContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path PROFILE = Path.of("../inventory-domain/src/test/resources/profiles/shop.json");

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;
    @Autowired @Qualifier("invDataSource") DataSource invDataSource;

    MockMvc mvc;
    JdbcTemplate sql;
    InventoryReservationClient client;
    String merchant;
    String systemBearer;
    String item;

    @BeforeEach
    void setUp() throws Exception {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
        sql = new JdbcTemplate(invDataSource);

        // 商家的账：业主凭证只用来备货；商城调用用的是系统凭证
        merchant = "M" + UUID.randomUUID().toString().substring(0, 8);
        String owner = field(admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"SHOP\",\"externalRef\":\"" + merchant + "\",\"name\":\"店\"}")), "ownerId");
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + Files.readString(PROFILE) + ",\"reason\":\"init\"}"))
                .andExpect(status().isOk());
        JsonNode ownerKey = JSON.readTree(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"stock\"}")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        String ownerBearer = "Bearer " + ownerKey.get("appKey").asString() + ":" + ownerKey.get("appSecret").asString();
        item = field(write(ownerBearer, put("/api/inventory/v1/items/by-ref/SKU-9").content("{\"name\":\"茶\"}")),
                "itemId");
        String wh = field(write(ownerBearer, post("/api/inventory/v1/warehouses").content("{\"name\":\"门店\"}")),
                "locationId");
        sql.update("update inv_location set external_ref = 'STORE-1' where location_id = ?", wh);
        String in = field(write(ownerBearer, post("/api/inventory/v1/inbounds").content(
                "{\"locationId\":\"%s\",\"purpose\":\"PURCHASE\",\"lines\":[{\"itemId\":\"%s\",\"qty\":5}]}"
                        .formatted(wh, item))), "docNo");
        write(ownerBearer, post("/api/inventory/v1/inbounds/" + in + "/post")).andExpect(status().isOk());

        JsonNode system = JSON.readTree(admin(post("/api/inventory/admin/v1/systems/SHOP/credentials")
                .content("{\"name\":\"shop-app\"}")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        systemBearer = "Bearer " + system.get("appKey").asString() + ":" + system.get("appSecret").asString();
        RestClient rest = RestClient.builder()
                .requestFactory(new MockMvcClientHttpRequestFactory(mvc))
                .baseUrl("http://inventory.test")
                .defaultHeader("Authorization", systemBearer)
                .requestInterceptor(InventoryReservationClient::decodeErrors)
                .defaultStatusHandler(s -> !s.is2xxSuccessful(), (req, res) -> {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, "inventory", res.getStatusCode().value(),
                            "inventory " + res.getStatusCode().value());
                })
                .build();
        HttpServiceProxyFactory proxies = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build();
        client = InventoryReservationClient.of(proxies.createClient(InventoryReservationApi.class),
                proxies.createClient(InventoryStockApi.class), proxies.createClient(InventoryConsoleApi.class),
                new CircuitBreaker(3, Duration.ofSeconds(30), Clock.systemUTC()));
    }

    @Test
    void shopReservesBySkuAndStoreWithASystemCredential() {
        InventoryReservationApi.View held = client.reserve(merchant, "SYSTEM:order",
                new InventoryReservationApi.Reserve("ORD-1",
                        List.of(new InventoryReservationApi.Line(null, null, "SKU-9", "STORE-1", 2, null)), 900, 1));
        assertThat(held.status()).isEqualTo("HELD");
        // 回来的行带 SKU 号：商城的快照按 SKU 比对
        assertThat(held.lines().getFirst().itemRef()).isEqualTo("SKU-9");
        assertThat(client.query(merchant, "ORD-1")).get().extracting(InventoryReservationApi.View::reservationId)
                .isEqualTo(held.reservationId());
        assertThat(client.query(merchant, "ORD-404")).isEmpty();

        // 业务错误原样回来（商城据此报「哪几件不够」），且不算服务故障
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.reserve(merchant, "SYSTEM:order",
                    new InventoryReservationApi.Reserve("ORD-BIG-" + UUID.randomUUID(),
                            List.of(new InventoryReservationApi.Line(null, null, "SKU-9", "STORE-1", 9, null)), 900, 1)))
                    .isInstanceOfSatisfying(InventoryException.class,
                            e -> assertThat(e.error()).isEqualTo(InventoryError.STOCK_SHORT));
        }
        assertThat(client.breakerOpen()).isFalse();

        assertThat(client.cancel(merchant, "SYSTEM:order", "ORD-1").status()).isEqualTo("RELEASED");
    }

    @Test
    void ownersStillWrittenByTheEmbeddedShopAreRefused() {
        sql.update("update inv_owner set write_authority = 'EMBEDDED' where system_code = 'SHOP' and external_ref = ?",
                merchant);
        assertThatThrownBy(() -> client.query(merchant, "ORD-1"))
                .isInstanceOfSatisfying(InventoryException.class,
                        e -> assertThat(e.error()).isEqualTo(InventoryError.NOT_WRITER));
        // 别的系统的业主、不存在的业主：都按「没有这个业主」
        assertThatThrownBy(() -> client.query("NOPE-" + UUID.randomUUID(), "ORD-1"))
                .isInstanceOfSatisfying(InventoryException.class,
                        e -> assertThat(e.error()).isEqualTo(InventoryError.NOT_FOUND));
    }

    @Test
    void systemLevelActionsByOrderNoTouchOnlyThisSystemsServiceWrittenMerchants() throws Exception {
        client.reserve(merchant, "SYSTEM:order", new InventoryReservationApi.Reserve("ORD-SYS",
                List.of(new InventoryReservationApi.Line(null, null, "SKU-9", "STORE-1", 1, "F-1")), 900, 2));
        // 同一单号在一个内嵌写的商家下也有（同库：电商套过渡期就是这样）—— 系统级动作不该碰它
        String embeddedOwner = field(admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"SHOP\",\"externalRef\":\"E-" + UUID.randomUUID().toString().substring(0, 8)
                        + "\",\"name\":\"内嵌店\"}")), "ownerId");
        sql.update("insert into inv_reservation (reservation_id, owner_id, external_ref, status, lifecycle_version,"
                + " expires_at) values (?, ?, 'ORD-SYS', 'HELD', 2, ?)", "RS-EMB-" + System.nanoTime(),
                embeddedOwner, java.time.LocalDateTime.now().plusHours(1));
        sql.update("update inv_owner set write_authority = 'EMBEDDED' where owner_id = ?", embeddedOwner);

        client.allocatePaidByRef("SYSTEM:order", "ORD-SYS");
        assertThat(client.query(merchant, "ORD-SYS")).get().extracting(InventoryReservationApi.View::status)
                .isEqualTo("ALLOCATED");
        assertThat(sql.queryForObject("select status from inv_reservation where owner_id = ? and external_ref = ?",
                String.class, embeddedOwner, "ORD-SYS")).isEqualTo("HELD");
        assertThat(client.issueByFulfillment("SYSTEM:order", "F-1")).isNotNull();
        assertThat(client.query(merchant, "ORD-SYS")).get().extracting(InventoryReservationApi.View::status)
                .isEqualTo("COMMITTED");

        // 退货回补、商品页改库存：按 SKU / 门店
        InventoryStockApi.Restored restored = client.restore(merchant, "SYSTEM:order", new InventoryStockApi.Restore(
                "AS-1", List.of(new InventoryReservationApi.Line(null, null, "SKU-9", "STORE-1", 1, null))));
        assertThat(restored.inboundNo()).isNotBlank();
        client.setOnHand(merchant, "SYSTEM:order",
                new InventoryStockApi.OnHand(null, "SKU-9", null, "STORE-1", 7, "OTHER", "GOODS_PAGE"));
        assertThat(sql.queryForObject("select on_hand from inv_stock_balance where item_id = ?",
                java.math.BigDecimal.class, item)).isEqualByComparingTo("7");

        // 系统级接口只认系统凭证
        mvc.perform(post("/api/inventory/v1/system/reservations/ORD-SYS/release")
                        .header("Authorization", ownerBearerOf(merchant)))
                .andExpect(status().isForbidden());
    }

    @Test
    void merchantConsoleReadsAndWritesGoThroughTheService() {
        InventoryConsole console = client.console(merchant, "STAFF:E9");
        InventoryVOs.SummaryVO summary = console.get("/stock/summary",
                InventoryConsole.params("storeRef", "STORE-1"), InventoryVOs.SummaryVO.class);
        assertThat(summary.itemCount()).isEqualTo(1);
        List<InventoryVOs.BalanceVO> balances = console.get("/stock/balances",
                InventoryConsole.params("filter", "all", "storeRef", "STORE-1", "size", 10), new TypeReference<>() { });
        assertThat(balances).singleElement().extracting(InventoryVOs.BalanceVO::itemId).isEqualTo(item);

        // 自己的 SKU 有账，别人的 / 不存在的回 null（不泄露存在与否）
        assertThat(console.get("/stock/item-by-sku", InventoryConsole.params("skuNo", "SKU-9"),
                InventoryVOs.ItemDetailVO.class)).isNotNull();
        assertThat(console.get("/stock/item-by-sku", InventoryConsole.params("skuNo", "SKU-NOPE"),
                InventoryVOs.ItemDetailVO.class)).isNull();

        // 单件改数 → 盘点单 → 流水可查
        console.post("/stock/adjust", java.util.Map.of("itemId", item, "countedQty", 3, "reasonCode", "OTHER",
                "storeRef", "STORE-1"));
        assertThat(sql.queryForObject("select on_hand from inv_stock_balance where item_id = ?",
                java.math.BigDecimal.class, item)).isEqualByComparingTo("3");
        InventoryVOs.LedgerPageVO ledger = console.get("/stock/ledger",
                InventoryConsole.params("itemId", item, "storeRef", "STORE-1"), InventoryVOs.LedgerPageVO.class);
        assertThat(ledger.entries()).hasSize(2);

        // 端上传来的库位号不是这本账的 → 403
        assertThatThrownBy(() -> console.get("/stock/balances", InventoryConsole.params("locationId", "LOC-OTHER"),
                new TypeReference<List<InventoryVOs.BalanceVO>>() { }))
                .isInstanceOfSatisfying(InventoryException.class,
                        e -> assertThat(e.error()).isEqualTo(InventoryError.FORBIDDEN));
    }

    @Test
    void merchantConsoleDocumentsHonourTheStaffStoreScope() {
        InventoryConsole console = client.console(merchant, "U9");
        // 入库：落在当前门店解析出的库位上
        String no = console.post("/docs/inbounds", InventoryConsole.params("storeRef", "STORE-1"),
                java.util.Map.of("sourceType", "PURCHASE", "lines",
                        List.of(java.util.Map.of("itemId", item, "qty", 4, "uom", "PCS", "unitCostMinor", 100))),
                InventoryVOs.DocNoVO.class).no();
        // 只管别家门店的员工：动不了这张单
        assertThatThrownBy(() -> console.postTo("/docs/inbounds/" + no + "/post",
                InventoryConsole.params("scopeStores", "STORE-OTHER"), null))
                .isInstanceOfSatisfying(InventoryException.class,
                        e -> assertThat(e.error()).isEqualTo(InventoryError.FORBIDDEN));
        // 管这家门店的员工 / 店主：可以
        console.postTo("/docs/inbounds/" + no + "/post", InventoryConsole.params("scopeStores", "STORE-1"), null);
        assertThat(sql.queryForObject("select on_hand from inv_stock_balance where item_id = ?",
                java.math.BigDecimal.class, item)).isEqualByComparingTo("9");
        assertThat(sql.queryForObject("select updated_by from inv_inbound_order where inbound_no = ?", String.class, no))
                .isEqualTo("U9");

        // 盘点：开单 → 录数 → 过账
        String count = console.post("/docs/counts", InventoryConsole.params("storeRef", "STORE-1"),
                java.util.Map.of("itemIds", List.of(item)), InventoryVOs.DocNoVO.class).no();
        console.putTo("/docs/counts/" + count + "/lines", InventoryConsole.params(),
                List.of(java.util.Map.of("itemId", item, "countedQty", 8, "reasonCode", "LOSS")));
        assertThat(console.get("/docs/counts/" + count, InventoryConsole.params(), InventoryVOs.CountVO.class)
                .countNo()).isEqualTo(count);
        console.postTo("/docs/counts/" + count + "/post", InventoryConsole.params(), null);
        assertThat(sql.queryForObject("select on_hand from inv_stock_balance where item_id = ?",
                java.math.BigDecimal.class, item)).isEqualByComparingTo("8");
    }

    @Test
    void merchantConsoleCatalogReportsAndValuationGoThroughTheService() {
        InventoryConsole console = client.console(merchant, "U9");
        List<ai.neargo.inventory.domain.entity.InvLocation> locs = console.get("/locations", InventoryConsole.params(),
                new TypeReference<>() { });
        assertThat(locs).extracting(ai.neargo.inventory.domain.entity.InvLocation::getExternalRef).contains("STORE-1");

        String supplier = console.post("/suppliers", java.util.Map.of("name", "茶园"),
                java.util.Map.class).get("supplierNo").toString();
        List<java.util.Map<String, Object>> list = console.get("/suppliers", InventoryConsole.params("activeOnly", true),
                new TypeReference<>() { });
        assertThat(list).extracting(m -> m.get("supplierNo")).contains(supplier);
        console.post("/suppliers/" + supplier + "/active", java.util.Map.of("active", false));
        assertThat(console.get("/suppliers", InventoryConsole.params("activeOnly", true),
                new TypeReference<List<java.util.Map<String, Object>>>() { })).isEmpty();

        String month = java.time.YearMonth.now().toString();
        InventoryVOs.MonthlyVO monthly = console.get("/report/monthly",
                InventoryConsole.params("month", month, "storeRef", "STORE-1"), InventoryVOs.MonthlyVO.class);
        assertThat(monthly.closing()).isEqualTo(5);
        assertThat(console.getText("/export", InventoryConsole.params("type", "ledger", "storeRef", "STORE-1")))
                .contains(",");

        var policy = new ai.neargo.inventory.domain.service.InventoryValuationService.Policy(0, "CNY", "LATEST", 120L, false);
        var saved = console.post("/items/" + item + "/valuation", InventoryConsole.params("key", "VAL-1"), policy,
                ai.neargo.inventory.domain.service.InventoryValuationService.Policy.class);
        // 同一把幂等键重放：同一个结果
        var replay = console.post("/items/" + item + "/valuation", InventoryConsole.params("key", "VAL-1"), policy,
                ai.neargo.inventory.domain.service.InventoryValuationService.Policy.class);
        assertThat(replay).isEqualTo(saved);
        assertThat(console.get("/items/" + item + "/valuation", InventoryConsole.params(),
                ai.neargo.inventory.domain.service.InventoryValuationService.Policy.class).unitCostMinor()).isEqualTo(120L);
    }

    private String ownerBearerOf(String merchantRef) throws Exception {
        String owner = sql.queryForObject("select owner_id from inv_owner where system_code = 'SHOP' and external_ref = ?",
                String.class, merchantRef);
        JsonNode key = JSON.readTree(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"k\"}")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        return "Bearer " + key.get("appKey").asString() + ":" + key.get("appSecret").asString();
    }

    private ResultActions write(String bearer, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer)
                .header("Idempotency-Key", "K-" + UUID.randomUUID()).header("X-Inventory-Actor", "STAFF:E1"));
    }

    private ResultActions admin(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON)
                .header("X-Inventory-Admin-Token", "admin-test-token").header("X-Inventory-Actor", "OPS:test"));
    }

    private static String field(ResultActions r, String name) throws Exception {
        return JSON.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get(name).asString();
    }
}
