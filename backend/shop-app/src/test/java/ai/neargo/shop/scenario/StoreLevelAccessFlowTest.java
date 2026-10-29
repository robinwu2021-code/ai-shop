package ai.neargo.shop.scenario;

import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 门店级访问：<b>只被授权到 A 店的店员，动不了同主体 B 店的资源</b>。
 *
 * <p>主体级校验（{@code requireMerchantNo}）只挡得住别家商家；同一主体下换个门店号，
 * 它照样放行。这里每条都用同一个夹具：一个主体、两家店（A 默认店 / B 分店），
 * 一个只在 A 店做店长（MANAGER）的店员 —— 断言他在 B 店被拒、在 A 店照常。
 *
 * <p>只断言「B 被拒」不够：把闸写成「一律拒」也能过。所以每条都带 A 店的正例。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("门店级访问：店员只能动被授权的店")
class StoreLevelAccessFlowTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final int FORBIDDEN = 10403;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InventoryAclService acl;
    @Autowired
    private ai.neargo.inventory.domain.service.LocationService locations;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 一个主体两家店，店员只在 A 店做店长 */
    private record Shop(String owner, String staff, String entityNo, String storeA, String storeB) {
    }

    // ---------------------------------------------------------------- 预约排期

    @Test
    @DisplayName("★★★ 预约排期：B 店的时段看不了、开不了、停不了；A 店照常")
    void appointmentSlotsAreStoreScoped() throws Exception {
        Shop s = shop();
        long now = System.currentTimeMillis();
        String range = "?from=" + now + "&to=" + (now + 7 * 86_400_000L);
        String slot = "{\"startAt\":" + (now + 3_600_000L) + ",\"endAt\":" + (now + 7_200_000L)
                + ",\"capacity\":1}";

        assertThat(code(get("/biz/stores/" + s.storeB() + "/appointment-slots" + range), s.staff()))
                .as("GET 此前连主体都不判，任何商家都能读任何店的档期").isEqualTo(FORBIDDEN);
        assertThat(code(get("/biz/stores/" + s.storeA() + "/appointment-slots" + range), s.staff())).isZero();

        assertThat(code(post("/biz/stores/" + s.storeB() + "/appointment-slots").content(slot), s.staff()))
                .isEqualTo(FORBIDDEN);
        assertThat(code(post("/biz/stores/" + s.storeA() + "/appointment-slots").content(slot), s.staff()))
                .isZero();

        String slotOfB = data(post("/biz/stores/" + s.storeB() + "/appointment-slots").content(slot), s.owner())
                .get("slotNo").asString();
        assertThat(code(post("/biz/appointment-slots/" + slotOfB + "/close"), s.staff()))
                .as("停 B 店的时段同样是动 B 店").isEqualTo(FORBIDDEN);
    }

    // ---------------------------------------------------------------- 送货方式

    @Test
    @DisplayName("★★★ 送货方式：B 店的读不了、改不了；A 店（含 default）照常")
    void fulfillmentSettingsAreStoreScoped() throws Exception {
        Shop s = shop();
        String body = "{\"channels\":[{\"channel\":\"STORE_PICKUP\",\"enabled\":true}]}";

        assertThat(code(put("/biz/stores/" + s.storeB() + "/fulfillment").content(body), s.staff()))
                .as("改 B 店送货方式").isEqualTo(FORBIDDEN);
        assertThat(code(get("/biz/stores/" + s.storeB() + "/fulfillment"), s.staff())).isEqualTo(FORBIDDEN);

        assertThat(code(get("/biz/stores/" + s.storeA() + "/fulfillment"), s.staff())).isZero();
        assertThat(code(get("/biz/stores/default/fulfillment"), s.staff()))
                .as("default = 主体默认店 = A 店，他有权").isZero();
        assertThat(code(put("/biz/stores/" + s.storeB() + "/fulfillment").content(body), s.owner()))
                .as("老板改 B 店照常 —— 闸只拦没授权的人").isZero();
    }

    // ---------------------------------------------------------------- 自提点

    @Test
    @DisplayName("★★ 自提点：B 店的候选看不了；A 店照常")
    void pickupPointsAreStoreScoped() throws Exception {
        Shop s = shop();
        assertThat(code(get("/biz/pickup-points/candidates?storeNo=" + s.storeB()), s.staff()))
                .isEqualTo(FORBIDDEN);
        assertThat(code(get("/biz/pickup-points/candidates?storeNo=" + s.storeA()), s.staff())).isZero();
    }

    // ---------------------------------------------------------------- 线下卖出

    @Test
    @DisplayName("★★ 线下卖出：B 店的流水看不了；A 店照常")
    void offlineSaleIsStoreScoped() throws Exception {
        Shop s = shop();
        assertThat(code(get("/biz/store/" + s.storeB() + "/offline-sale"), s.staff())).isEqualTo(FORBIDDEN);
        assertThat(code(get("/biz/store/" + s.storeA() + "/offline-sale"), s.staff())).isZero();
    }

    // ---------------------------------------------------------------- 库存同步

    @Test
    @DisplayName("★★ 库存同步：B 店的同步状态、可售规则、库存总览看不了；A 店照常")
    void stockSyncIsStoreScoped() throws Exception {
        Shop s = shop();
        for (String path : List.of("/stock-sync", "/sell-rules", "/stock-alignment")) {
            assertThat(code(get("/biz/store/" + s.storeB() + path), s.staff()))
                    .as("B 店 " + path).isEqualTo(FORBIDDEN);
            assertThat(code(get("/biz/store/" + s.storeA() + path), s.staff()))
                    .as("A 店 " + path).isZero();
        }
    }

    // ---------------------------------------------------------------- 售后

    @Test
    @DisplayName("★★★ 售后：B 店的单列表里看不到、驳回不了；A 店照常")
    void afterSaleIsStoreScoped() throws Exception {
        Shop s = shop();
        String asA = seedAfterSale(s, s.storeA());
        String asB = seedAfterSale(s, s.storeB());

        List<String> listed = new ArrayList<>();
        data(get("/biz/after-sale"), s.staff()).forEach(n -> listed.add(n.get("afterSaleNo").asString()));
        assertThat(listed).as("列表按他能动的店过滤").contains(asA).doesNotContain(asB);

        List<String> ownerListed = new ArrayList<>();
        data(get("/biz/after-sale"), s.owner()).forEach(n -> ownerListed.add(n.get("afterSaleNo").asString()));
        assertThat(ownerListed).as("老板看全部").contains(asA, asB);

        String reject = "{\"remark\":\"不符合退款条件\"}";
        assertThat(code(post("/biz/after-sale/" + asB + "/reject").content(reject), s.staff()))
                .isEqualTo(FORBIDDEN);
        assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",
                String.class, asB)).as("被拒的那一下什么都没落").isEqualTo("APPLIED");
        for (String action : List.of("approve", "receive", "confirm-refund")) {
            assertThat(code(post("/biz/after-sale/" + asB + "/" + action).content("{}"), s.staff()))
                    .as("B 店售后的 %s", action).isEqualTo(FORBIDDEN);
        }

        assertThat(code(post("/biz/after-sale/" + asA + "/reject").content(reject), s.staff())).isZero();
    }

    // ---------------------------------------------------------------- 进销存单据

    @Test
    @DisplayName("★★★ 进销存：B 店自有库位上的单据过不了账、作废不了、看不了；调拨不能调进 B 店")
    void stockDocsAreStoreScoped() throws Exception {
        Shop s = shop();
        String item = acl.upsertItem(s.entityNo(), "SKU-SLA-" + SEQ.incrementAndGet(), "东北大米", "5斤装",
                null, null, "袋");

        // B 店改成「发自己的」：它的单据落在 B 店自己的库位上，不再与 A 店共用主体默认仓
        String inboundBody = """
                {"sourceType":"PURCHASE","supplierName":"老周粮油","occurredAt":"2026-08-01T00:00:00",
                 "lines":[{"itemId":"%s","qty":10,"uom":"袋","unitCostMinor":4200}]}
                """.formatted(item);
        String locB = acl.locationIdOf(s.entityNo(), s.storeB());
        assertThat(code(put("/biz/inventory/locations/" + locB + "/source").content("{}"), s.owner())).isZero();

        String inboundB = data(atStore(post("/biz/inventory/inbounds").content(inboundBody), s.storeB()),
                s.owner()).get("no").asString();
        assertThat(code(put("/biz/inventory/inbounds/" + inboundB).content(inboundBody), s.staff()))
                .as("改 B 店的进货草稿").isEqualTo(FORBIDDEN);
        assertThat(code(post("/biz/inventory/inbounds/" + inboundB + "/post"), s.staff()))
                .as("给 B 店入账").isEqualTo(FORBIDDEN);
        assertThat(code(post("/biz/inventory/inbounds/" + inboundB + "/void"), s.staff())).isEqualTo(FORBIDDEN);
        assertThat(code(post("/biz/inventory/inbounds/" + inboundB + "/post"), s.owner()))
                .as("老板照常").isZero();

        String countB = data(atStore(post("/biz/inventory/counts").content("{\"itemIds\":[\"" + item + "\"]}"),
                s.storeB()), s.owner()).get("no").asString();
        assertThat(code(get("/biz/inventory/counts/" + countB), s.staff())).isEqualTo(FORBIDDEN);
        assertThat(code(post("/biz/inventory/counts/" + countB + "/void"), s.staff())).isEqualTo(FORBIDDEN);

        // 调拨：从 A 店的出货库位调进 B 店 —— 目的地他无权
        // 与控制器同一套解析：A 店的出货库位（新门店默认指向主体默认仓）
        String locA = locations.resolveStockLocation(acl.ownerIdOf(s.entityNo()),
                acl.locationIdOf(s.entityNo(), s.storeA()));
        String transferBody = """
                {"fromLocationId":"%s","toLocationId":"%s","lines":[{"itemId":"%s","qty":1}]}
                """;
        assertThat(code(post("/biz/inventory/transfers").content(transferBody.formatted(locA, locB, item)),
                s.staff())).as("调进 B 店").isEqualTo(FORBIDDEN);
        assertThat(code(post("/biz/inventory/transfers").content(transferBody.formatted(locB, locA, item)),
                s.staff())).as("从 B 店调出").isEqualTo(FORBIDDEN);

        // A 店照常：他自己的进货单建得出、过得了账
        String inboundA = data(post("/biz/inventory/inbounds").content(inboundBody), s.staff())
                .get("no").asString();
        assertThat(code(post("/biz/inventory/inbounds/" + inboundA + "/post"), s.staff())).isZero();
    }

    // ---------------------------------------------------------------- 夹具

    private Shop shop() throws Exception {
        int seq = SEQ.incrementAndGet();
        String ownerPhone = "126017700%02d".formatted(seq);
        String staffPhone = "126017701%02d".formatted(seq);
        String owner = merchant(ownerPhone, "门店级访问-" + seq);
        String entityNo = data(get("/biz/context"), owner).get("merchantNo").asString();
        TestPlan.grantPro(planMapper, entityNo);

        String storeA = data(get("/biz/store/list"), owner).get(0).get("storeNo").asString();
        String storeB = data(post("/biz/store/create").content(
                "{\"name\":\"门店级访问分店-" + seq + "\",\"address\":\"某某路 8 号\"}"), owner)
                .get("storeNo").asString();

        String accountNo = data(post("/biz/staff").content("{\"loginPhone\":\"" + staffPhone + "\"}"), owner)
                .get("mchAccountNo").asString();
        mvc().perform(post("/biz/staff/" + accountNo + "/store")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + storeA + "\",\"role\":\"MANAGER\"}"))
                .andExpect(jsonPath("$.code").value(0));
        String staff = TestLogin.merchantStaff(mvc(), json, otpStore, staffPhone);
        return new Shop(owner, staff, entityNo, storeA, storeB);
    }

    /** 一张落在指定门店的子单 + 一张待处理（APPLIED）的仅退款售后 */
    private String seedAfterSale(Shop s, String storeNo) {
        String k = "SLA" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 1_000_000;
        String order = "ORD-" + k;
        String sub = "SUB-" + k;
        String as = "AS-" + k;
        jdbc.update("insert into ord_order(order_no,user_no,status,pay_amount,goods_amount,currency,tenant_no,"
                + "created_at,updated_at) values (?, 'U-SLA', 'PAID', 1000, 1000, 'CNY', 'MAIN', now(), now())", order);
        jdbc.update("insert into ord_sub_order(sub_order_no, order_no, user_no, entity_no, store_no, fulfillment,"
                + " status, pay_amount, created_at, updated_at) values (?, ?, 'U-SLA', ?, ?, 'EXPRESS',"
                + " 'WAIT_FULFILL', 1000, now(), now())", sub, order, s.entityNo(), storeNo);
        jdbc.update("insert into ord_after_sale (after_sale_no, sub_order_no, order_no, user_no, entity_no, type,"
                + " status, reason, refund_minor, tenant_no, deleted, version, created_at, updated_at)"
                + " values (?, ?, ?, 'U-SLA', ?, 'REFUND', 'APPLIED', '测试', 100, 'MAIN', 0, 0, now(), now())",
                as, sub, order, s.entityNo());
        return as;
    }

    private MockHttpServletRequestBuilder atStore(MockHttpServletRequestBuilder req, String storeNo) {
        return req.header("X-Store-No", storeNo);
    }

    private JsonNode envelope(MockHttpServletRequestBuilder req, String token) throws Exception {
        return json.readTree(mvc().perform(req.header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString());
    }

    private int code(MockHttpServletRequestBuilder req, String token) throws Exception {
        return envelope(req, token).get("code").asInt();
    }

    private JsonNode data(MockHttpServletRequestBuilder req, String token) throws Exception {
        JsonNode env = envelope(req, token);
        assertThat(env.get("code").asInt()).as("夹具这一步应当成功，msg=%s", env.get("msg")).isZero();
        return env.get("data");
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }
}
