package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestStoreCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 快递单按运费模板收运费（TDD-快递100商家寄件 §8 AC15）。**走真实下单链路**：此前运费在 split 里恒 0，
 * 模板「只存不算」—— 这一层不测，计价公式再对也收不到钱。
 *
 * <p>读种子默认模板 FT0001（首重 1000g ¥8、续重 500g ¥2、满 ¥99 包邮、西藏自治区不配送），不改它。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("快递单运费：按模板写进子单、算进实付；不配送的地区拒单")
class FreightOrderFlowTest {

    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ai.neargo.shop.paybridge.SettleGenerationOrchestrator settleOrchestrator;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 快递单：没填重量的货按首重收运费 ¥8，写进子单、算进实付；预览与下单同一个数")
    void expressOrderPaysTemplateFreight() throws Exception {
        String goodsNo = onSaleGoods(merchant("12600390001", "寄快递的店"), "小米");
        JsonNode created = place("12600390002", goodsNo, "浙江省");
        assertThat(created.path("code").asInt()).isZero();
        String payOrderNo = created.path("data").path("payOrderNo").asString();

        Map<String, Object> sub = jdbc.queryForMap(
                "select freight_amount, pay_amount, goods_amount from ord_sub_order where order_no=?", payOrderNo);
        assertThat(((Number) sub.get("freight_amount")).longValue()).as("此前恒 0").isEqualTo(800L);
        assertThat(((Number) sub.get("pay_amount")).longValue())
                .isEqualTo(((Number) sub.get("goods_amount")).longValue() + 800L);
        assertThat(jdbc.queryForObject("select freight_amount from ord_order where order_no=?", Long.class, payOrderNo))
                .isEqualTo(800L);
    }

    @Test
    @DisplayName("★★ 收货地址命中「不配送」地区：下单即拒（20003），不让买家付完钱才发现寄不到")
    void rejectedRegionBlocksCreate() throws Exception {
        String goodsNo = onSaleGoods(merchant("12600390003", "不送西藏的店"), "大米");
        JsonNode created = place("12600390004", goodsNo, "西藏自治区");
        assertThat(created.path("code").asInt()).isEqualTo(20003);
    }

    @Test
    @DisplayName("★★★ 运费不进佣金基数 —— 此前平台对代收的运费也抽了佣金（§9 AC21）")
    void freightIsNotInCommissionBase() throws Exception {
        String goodsNo = onSaleGoods(merchant("12600390005", "结算运费店"), "面粉");
        JsonNode created = place("12600390006", goodsNo, "浙江省");
        assertThat(created.path("code").asInt()).isZero();
        String payOrderNo = created.path("data").path("payOrderNo").asString();

        pay(payOrderNo);
        settleOrchestrator.generateForOrder(payOrderNo);

        Map<String, Object> bill = jdbc.queryForMap(
                "select gross_minor, freight_income_minor, freight_cost_minor, freight_ship_mode"
                        + " from stl_bill where order_no=?", payOrderNo);
        long goodsAmount = jdbc.queryForObject(
                "select goods_amount from ord_sub_order where order_no=?", Long.class, payOrderNo);

        /*
         * ★ 这一条是整个 A 批的判据。
         *
         * 改之前 gross = payAmount(货款 + 运费) + 补贴 + 积分，而
         * commission = (gross − 通道费) × rate —— 对代收的运费也抽了佣金。
         * 那笔钱是要转付给快递公司的，不是商家的营业额。
         */
        assertThat(((Number) bill.get("gross_minor")).longValue())
                .as("结算基数应当只装货款 —— 含了运费就是让商家为平台代收的钱付佣金")
                .isEqualTo(goodsAmount);

        assertThat(((Number) bill.get("freight_income_minor")).longValue())
                .as("运费要单列出来，否则账单上根本看不到这笔")
                .isEqualTo(800L);

        /*
         * 商家自己填单号发货 = 自付，平台一分没出，不扣 —— 扣了就是收两遍。
         * 判据是 shipMode 而不是「cost 是不是 0」：平台代寄但还没称重回传时 cost 也是 0。
         */
        assertThat(bill.get("freight_ship_mode"))
                .as("没有寄件记录就是商家自己寄的")
                .isEqualTo("MERCHANT_SELF");
        assertThat(((Number) bill.get("freight_cost_minor")).longValue())
                .as("商家自寄，平台没垫钱，不该扣")
                .isZero();
    }

    /** 走支付回调把单推到已付款 —— 只调 /pay 的话单还在 WAIT_PAY，结算单生不出来 */
    private void pay(String payOrderNo) throws Exception {
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-"
                                + payOrderNo + "\",\"sign\":\"stub-secret\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
    }

    @Test
    @DisplayName("★★★ 商品级限购地区（#3/#4①）：收货地址省在 restricted_regions 里 → 下单拒(20003)")
    void restrictedRegionBlocksCreate() throws Exception {
        String goodsNo = onSaleGoods(merchant("12600390021", "不卖新疆的店"), "坚果");
        // 这件货限购新疆（省级码 65）—— 与运费模板无关，走商品级 restricted_regions
        jdbc.update("update prd_goods set restricted_regions=? where goods_no=?", "[\"65\"]", goodsNo);

        // 收货地址在新疆 → 命中限购，拒单（运费模板里新疆只是 SURCHARGE 不拒，这道才拦得住）
        JsonNode blocked = place("12600390022", goodsNo, "新疆维吾尔自治区");
        assertThat(blocked.path("code").asInt()).as("新疆被该商品限购，应拒").isEqualTo(20003);

        // 收货地址在浙江（不在限购名单）→ 放行
        JsonNode ok = place("12600390023", goodsNo, "浙江省");
        assertThat(ok.path("code").asInt()).as("浙江不在限购名单，应放行").isZero();
    }

    @Test
    @DisplayName("★★★ 门店经营范围排除了新疆：寄往新疆的快递单下单即拒(20003)，浙江照常（TDD-经营范围排除地区 AC3）")
    void storeExcludedProvinceBlocksCreate() throws Exception {
        String goodsNo = onSaleGoods(merchant("12600390031", "门店不送新疆"), "核桃");
        Map<String, Object> st = jdbc.queryForMap(
                "select s.entity_no, s.store_no from mch_store s join prd_goods g on g.entity_no = s.entity_no"
                        + " where g.goods_no = ? and s.is_default = 1 and s.deleted = 0", goodsNo);
        // 商品本身不设限购 —— 拦住它的只能是门店那条排除
        jdbc.update("insert into mch_service_area (area_no, entity_no, store_no, level, ref_code, source, status, mode,"
                        + " created_at, updated_at) values (?, ?, ?, 'PROVINCE', '65', 'SELF', 'ACTIVE', 'EXCLUDE', now(), now())",
                "SA-EXCL-" + goodsNo, st.get("entity_no"), st.get("store_no"));

        JsonNode blocked = place("12600390032", goodsNo, "新疆维吾尔自治区");
        assertThat(blocked.path("code").asInt()).as("门店排除了新疆，应拒").isEqualTo(20003);

        JsonNode ok = place("12600390033", goodsNo, "浙江省");
        assertThat(ok.path("code").asInt()).as("对照：浙江没被排除，应放行").isZero();
    }

    // ── helpers（与 TodoPickupScopeFlowTest 同一套下单脚手架） ─────────────────

    private JsonNode place(String phone, String goodsNo, String province) throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, phone);
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));
        String addressId = json.readTree(mvc().perform(post("/mp/user/address")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"买家\",\"phone\":\"13600180013\",\"province\":\"" + province + "\","
                                + "\"city\":\"某市\",\"district\":\"某区\",\"detail\":\"某路 1 号\","
                                + "\"isDefault\":true,\"tag\":\"家\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get(0).get("addressId").asString();
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "freight-" + phone)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"EXPRESS\",\"addressId\":\"" + addressId + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String onSaleGoods(String merchantToken, String title) throws Exception {
        TestStoreCategory.open(mvc(), json, merchantToken, "CAT210");
        String body = mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"type\":\"NORMAL\",\"categoryNo\":\"CAT210\","
                                + "\"skus\":[{\"spec\":\"默认\",\"price\":500,\"stock\":99}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return goodsNo;
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
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }
}
