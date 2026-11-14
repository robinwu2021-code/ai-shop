package ai.neargo.inventory.svc;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.client.CircuitBreaker;
import ai.neargo.inventory.client.InventoryClient;
import ai.neargo.inventory.client.InventoryHostApi;
import ai.neargo.inventory.client.InventoryHostApi.InLine;
import ai.neargo.inventory.client.InventoryHostApi.InboundCreate;
import ai.neargo.inventory.client.InventoryHostApi.ItemSpec;
import ai.neargo.inventory.client.InventoryHostApi.OutLine;
import ai.neargo.inventory.client.InventoryHostApi.OutboundCreate;
import ai.neargo.inventory.client.InventoryHostApi.OwnerKey;
import ai.neargo.inventory.client.InventoryHostApi.OwnerRef;
import ai.neargo.inventory.client.InventoryHostApi.QtyLine;
import ai.neargo.inventory.client.InventoryHostApi.Receive;
import ai.neargo.inventory.client.InventoryHostApi.Received;
import ai.neargo.inventory.client.InventoryHostApi.SerialIn;
import ai.neargo.inventory.client.InventoryHostApi.TargetSync;
import ai.neargo.inventory.client.InventoryHostApi.TransferCreate;
import ai.neargo.inventory.client.InventoryHostApi.WarehouseCreate;
import ai.neargo.inventory.client.InventoryReservationApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一客户端对真实控制器（TDD §2.23，AC-46 ~ 48）：同一个 {@link InventoryClient}，
 * 电商用系统凭证 + 账本头，ShareHub 用账本凭证；稳定命令键可重发、可查结果；错误码原样回到调用方。
 * 传输走 MockMvc，客户端的错误解码与熔断是真的。
 */
@SpringBootTest(properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_unified_client;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=TEST",
        "inventory.instance.allowed-systems=SHOP,SHAREHUB",
        "inventory.admin.token=admin-test-token"})
class InventoryClientContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SHAREHUB = """
            {"schemaVersion":1,"capabilities":["SERIAL","DISPATCH_POOL"],
             "targetTypes":[{"code":"MERCHANT","nameKey":"m","parentType":null},
                            {"code":"SITE","nameKey":"s","parentType":"MERCHANT"},
                            {"code":"PERSON","nameKey":"p","parentType":null}],
             "inboundPurposes":[{"code":"PURCHASE","basisRequired":false,"attachmentRequired":false}],
             "outboundPurposes":[{"code":"ISSUE","requiredTargets":["MERCHANT","PERSON"],
                                  "optionalTargets":["SITE"],"handover":"POOL"}],
             "gateRefTypes":["QC"],"countReview":{"required":false,"reviewerMustDiffer":false}}""";

    @Autowired WebApplicationContext context;
    @Autowired List<FilterRegistrationBean<?>> filters;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        filters.forEach(f -> builder.addFilters(f.getFilter()));
        mvc = builder.build();
    }

    @Test
    void ecommerceUsesOneSystemCredentialAcrossBooksWithHostNumbers() throws Exception {
        String shop = Files.readString(Path.of("../inventory-domain/src/test/resources/profiles/shop.json"));
        String chain = "CH" + UUID.randomUUID().toString().substring(0, 8);
        String store = "S" + UUID.randomUUID().toString().substring(0, 8);
        open("SHOP", "CHAIN", chain, shop);
        open("SHOP", "STORE", store, shop);
        InventoryClient client = client(systemCredential("SHOP"));
        InventoryClient.Session hq = client.owner(chain, "CHAIN", "STAFF:HQ");
        InventoryClient.Session branch = client.owner(store, "STORE", "STAFF:S1");

        // AC-46：同一个客户端、按账本头切换；只用宿主编号
        hq.createWarehouse("wh-hq", new WarehouseCreate("总仓", "HQ", "E100"));
        branch.createWarehouse("wh-" + store, new WarehouseCreate("门店", store, "E200"));
        hq.upsertItem("item-tea", "SKU-TEA", new ItemSpec("茶", null, null, null, null, null));
        String in = hq.createInbound("in-1", new InboundCreate(null, "HQ", "PURCHASE", null, null, null, null,
                List.of(new InLine(null, "SKU-TEA", 10, null, null))));
        hq.postInbound("in-1-post", in);
        hq.syncTarget("t-" + store, "STORE", store, new TargetSync("门店", null, null, 1L,
                new OwnerRef("STORE", store), store));
        String out = hq.createOutbound("out-1", new OutboundCreate(null, "HQ", "STORE_OUT", null,
                Map.of("STORE", store), null, List.of(new OutLine(null, "SKU-TEA", 4, null)), null));
        hq.postOutbound("out-1-post", out);
        assertThat(hq.inTransit()).extracting(InventoryHostApi.InTransit::kind).containsExactly("PAIRED");

        String pending = branch.pairedTransfers("IN", "SHIPPED").getFirst().get("to").get("docNo").asString();
        branch.receive("rcv-1", pending, new Receive(List.of(new Received(null, "SKU-TEA", 4))));
        assertThat(branch.balances("SKU-TEA", store)).singleElement()
                .satisfies(b -> assertThat(b.onHand()).isEqualByComparingTo("4"));

        // 连锁看各店：系统级批量余额
        var across = client.balancesAcross(List.of(new OwnerKey("CHAIN", chain), new OwnerKey("STORE", store)),
                Set.of("SKU-TEA"));
        assertThat(across).hasSize(2);
        assertThat(across.get(0).balances().getFirst().onHand()).isEqualByComparingTo("6");
        assertThat(across.get(1).balances().getFirst().locationRef()).isEqualTo(store);

        // 预留走同一个客户端（共用熔断）
        InventoryReservationApi.View held = client.reservations().reserve(store, "SYSTEM:order",
                new InventoryReservationApi.Reserve("ORD-" + store, List.of(new InventoryReservationApi.Line(
                        null, null, "SKU-TEA", store, 1, null)), 900, null));
        assertThat(held.status()).isEqualTo("HELD");
    }

    @Test
    void sharehubUsesAnOwnerCredentialWithStableKeysThatReplay() throws Exception {
        String owner = open("SHAREHUB", "PLATFORM", "pb-" + UUID.randomUUID(), SHAREHUB);
        InventoryClient client = client(ownerCredential(owner));
        InventoryClient.Session pb = client.self("STAFF:E1");

        pb.createWarehouse("wh-a", new WarehouseCreate("A", "WH-A", null));
        pb.createWarehouse("wh-b", new WarehouseCreate("B", "WH-B", null));
        pb.upsertItem("item-cab", "CAB", new ItemSpec("柜", null, "SERIAL", "REF_REQUIRED", "QC", null));
        pb.syncTarget("t-m", "MERCHANT", "AG001", new TargetSync("商家", null, null, 1L, null, null));
        pb.syncTarget("t-p", "PERSON", "E1001", new TargetSync("运维", null, null, 1L, null, null));
        String in = pb.createInbound("in-c1", new InboundCreate(null, "WH-A", "PURCHASE", null, null, null, null,
                List.of(new InLine(null, "CAB", null, null, List.of(new SerialIn("C1", "QC-C1", null),
                        new SerialIn("C2", "QC-C2", null))))));
        pb.postInbound("in-c1-post", in);

        // AC-47：稳定命令键 —— 重发回放原结果，不重复建单；可按键查结果
        String issue = pb.createOutbound("issue-ORD9", new OutboundCreate(null, "WH-A", "ISSUE", null,
                Map.of("MERCHANT", "AG001", "PERSON", "E1001"), null, List.of(), List.of("C1")));
        assertThat(pb.createOutbound("issue-ORD9", new OutboundCreate(null, "WH-A", "ISSUE", null,
                Map.of("MERCHANT", "AG001", "PERSON", "E1001"), null, List.of(), List.of("C1")))).isEqualTo(issue);
        assertThat(pb.command("issue-ORD9").toString()).contains(issue);
        pb.postOutbound("issue-ORD9-post", issue);
        String dispatchId = pb.dispatchPool(List.of("AVAILABLE"), List.of("AG001"), "C1").getFirst()
                .get("dispatchId").asString();
        assertThat(pb.claim("sign-ORD9", List.of(dispatchId), "SIGN-ORD9").dispatchIds()).containsExactly(dispatchId);

        // 调拨 SN 物料：发运后在途、收货后在 B
        String transfer = pb.createTransfer("trf-1", new TransferCreate(null, "WH-A", null, "WH-B", List.of(),
                List.of("C2"), null));
        pb.shipTransfer("trf-1-ship", transfer);
        // C1 已签收，不再算在外；只剩调拨在途
        assertThat(pb.inTransit()).extracting(InventoryHostApi.InTransit::kind).containsExactly("TRANSFER");
        pb.receiveTransfer("trf-1-receive", transfer);
        assertThat(pb.serials("CAB", "WH-B")).extracting(InventoryHostApi.Serial::serialNo).containsExactly("C2");
        assertThat(pb.events(null, 100).get("events").findValuesAsString("type"))
                .contains("OutboundPosted", "TransferShipped", "TransferReceived");

        // AC-48：业务错误原样回来；同键异参 409
        assertThatThrownBy(() -> pb.createOutbound("issue-ORD9", new OutboundCreate(null, "WH-A", "ISSUE", null,
                Map.of("MERCHANT", "AG001", "PERSON", "E1001"), null, List.of(), List.of("C2"))))
                .isInstanceOfSatisfying(InventoryException.class,
                        e -> assertThat(e.error()).isEqualTo(InventoryError.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> pb.balances("NOPE", null))
                .isInstanceOfSatisfying(InventoryException.class,
                        e -> assertThat(e.error()).isEqualTo(InventoryError.NOT_FOUND));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private InventoryClient client(String bearer) {
        RestClient rest = RestClient.builder()
                .requestFactory(new MockMvcClientHttpRequestFactory(mvc))
                .baseUrl("http://inventory.test")
                .defaultHeader("Authorization", bearer)
                .requestInterceptor(InventoryReservationClient::decodeErrors)
                .defaultStatusHandler(s -> !s.is2xxSuccessful(), (req, res) -> {
                    throw new ServiceCallException(CallOutcome.REMOTE_ERROR, "inventory", res.getStatusCode().value(),
                            "inventory " + res.getStatusCode().value());
                })
                .build();
        HttpServiceProxyFactory proxies = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build();
        CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(10), Clock.systemUTC());
        return InventoryClient.of(proxies.createClient(InventoryHostApi.class),
                InventoryReservationClient.of(proxies.createClient(InventoryReservationApi.class), breaker));
    }

    private String open(String system, String type, String ref, String profile) throws Exception {
        String owner = field(admin(post("/api/inventory/admin/v1/owners").content(
                "{\"systemCode\":\"%s\",\"externalType\":\"%s\",\"externalRef\":\"%s\",\"name\":\"n\"}"
                        .formatted(system, type, ref))), "ownerId");
        admin(put("/api/inventory/admin/v1/owners/" + owner + "/profile")
                .content("{\"profile\":" + profile + ",\"reason\":\"init\"}")).andExpect(status().isOk());
        return owner;
    }

    private String systemCredential(String system) throws Exception {
        JsonNode issued = JSON.readTree(admin(post("/api/inventory/admin/v1/systems/" + system + "/credentials")
                .content("{\"name\":\"app\"}")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        return "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString();
    }

    private String ownerCredential(String owner) throws Exception {
        JsonNode issued = JSON.readTree(admin(post("/api/inventory/admin/v1/owners/" + owner + "/credentials")
                .content("{\"name\":\"app\"}")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        return "Bearer " + issued.get("appKey").asString() + ":" + issued.get("appSecret").asString();
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
