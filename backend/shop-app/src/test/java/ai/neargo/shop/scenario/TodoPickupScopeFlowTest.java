package ai.neargo.shop.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 工作台的「待分拣 / 待核销」与自提点页面必须<b>是同一批单</b>。
 *
 * <p>为什么需要这个文件：这两个数原先按<b>门店</b>算，而分拣单与核销台按<b>自提点</b>取。
 * 两个维度在单店自营自提点时恰好重合，所以所有既有测试都是绿的 ——
 * 直到买家把货收到别家的自提点：
 *
 * <pre>
 *   工作台：待分拣 1        ← 这单的 store_no 是我的
 *   分拣单：共 0 件         ← 这单的 pickup_no 不是我的点
 * </pre>
 *
 * <p>商家看到的是<b>「有活，但找不到」</b>，而两边的代码各自都说得通。
 * 一个自提点承接多家商家的货（ADR-005），所以 {@code pickup_no} 与 {@code store_no}
 * 本来就可能不同 —— 这不是脏数据，是正常业务。
 *
 * <p>钉的不是实现，是那条**跨两个模块才成立**的不变量：
 * <b>工作台上那个数字，必须等于点进去看到的条数。</b>
 */
@SpringBootTest
@ActiveProfiles("test")
class TodoPickupScopeFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 待分拣的数 = 分拣单的条数 —— 买家在别家的点取货时也要成立")
    void toPickMatchesPickingList() throws Exception {
        /*
         * 新商家（他自己没有任何自提点），买家把货收到 PP0001 ——
         * PP0001 属于种子商家 M0001，不属于他。
         */
        String supplier = merchant("12600260001", "只供货不设点");
        String goodsNo = onSaleGoods(supplier, "青菜");
        placeAndPayPickup("12600260002", goodsNo, "PP0001");

        // 按门店算的话这里是 1
        assertThat(todo(supplier, "toPick"))
                .as("他没有自提点，工作台不该报一个他打不开的活")
                .isZero();

        /*
         * 而他**根本打不开**自提点页面：没有自提点的人查自提单是 10403
         * （「你查错点了」，不是空列表 —— 见 PickupServiceImpl.requireScope）。
         *
         * 这条断言比「两个数都是 0」更贴近缺陷本身：旧口径下工作台报「待分拣 1」，
         * 而唯一能去的地方回他一个 403。**数字与去向必须同时成立，否则那个数就是假的。**
         */
        mvc().perform(get("/biz/pickup/orders").header("Authorization", "Bearer " + supplier))
                .andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    @DisplayName("★★★ 承接方看得到别家商家送来的货 —— 那正是他要分的")
    void pickupOwnerSeesOtherMerchantsGoods() throws Exception {
        String owner = loginAsOwnerOf("M0001", "12600260010");
        int before = todo(owner, "toPick");
        int beforeRows = pickingRows(owner);

        // 另一家商家的货，收货点选 PP0001（M0001 承接）
        String supplier = merchant("12600260011", "借点铺货的店");
        String goodsNo = onSaleGoods(supplier, "土豆");
        placeAndPayPickup("12600260012", goodsNo, "PP0001");

        assertThat(todo(owner, "toPick"))
                .as("一个自提点承接多家商家的货，别家的货同样要我分")
                .isEqualTo(before + 1);
        // 真正的验收点：两个数一起动，且动的幅度一样
        assertThat(pickingRows(owner)).isEqualTo(beforeRows + 1);
    }

    @Test
    @DisplayName("★★ 发货那两个数仍按门店 —— 它们是商家的活，与自提点无关")
    void shippingCountsStayOnStoreScope() throws Exception {
        String supplier = merchant("12600260020", "发快递的店");
        String goodsNo = onSaleGoods(supplier, "大米");
        placeAndPayExpress("12600260021", goodsNo);

        assertThat(todo(supplier, "toShip"))
                .as("他没有自提点，但快递单照样是他要发的")
                .isEqualTo(1);
        assertThat(todo(supplier, "toPick")).isZero();
    }

    // ---------------------------------------------------------------- 装配

    private int todo(String token, String field) throws Exception {
        String body = mvc().perform(get("/biz/dashboard/todo")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get(field).asInt();
    }

    /** 分拣单是按商品聚合的行，这里数「有多少个买家的单」才与 toPick 可比 */
    private int pickingRows(String token) throws Exception {
        String body = mvc().perform(get("/biz/pickup/orders")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "WAIT_FULFILL"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").size();
    }

    private void placeAndPayPickup(String phone, String goodsNo, String pickupNo) throws Exception {
        place(phone, goodsNo,
                "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"" + pickupNo + "\"}");
    }

    private void placeAndPayExpress(String phone, String goodsNo) throws Exception {
        place(phone, goodsNo, "{\"fulfillment\":\"EXPRESS\"}");
    }

    private void place(String phone, String goodsNo, String orderBody) throws Exception {
        String token = login(phone);
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));

        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "todo-scope-" + phone)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();
        // 付款后才进 WAIT_FULFILL —— 未付款的单不是待办（那是买家的事）
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-todo-"
                        + phone + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
    }

    private String onSaleGoods(String merchantToken, String title) throws Exception {
        String body = mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"type\":\"NORMAL\",\"categoryNo\":\"CAT210\","
                                + "\"skus\":[{\"spec\":\"默认\",\"price\":500,\"stock\":99}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();

        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return goodsNo;
    }

    /** 把某个手机号变成种子商家的店主，从而拿到它的自提点作用域 */
    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = login(phone);
        String userNo = json.readTree(mvc().perform(get("/mp/user/profile")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("userNo").asString();
        seedOwner(merchantNo, userNo);
        return login(phone);
    }

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper accountMapper;

    private void seedOwner(String entityNo, String userNo) {
        var row = new ai.neargo.shop.merchant.entity.MchAccount();
        row.setMchAccountNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.MERCHANT_STAFF));
        row.setEntityNo(entityNo);
        row.setUserNo(userNo);
        row.setIsOwner(true);
        row.setIsPrimary(true);
        row.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
        accountMapper.insert(row);
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
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
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return login(phone);
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String login(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
