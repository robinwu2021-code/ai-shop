package ai.neargo.shop.scenario;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交付项 D1/D2：商家收到单之后要做的第一件事 —— 打开这一单、发货、标记送达。
 *
 * <p>此前 B 端有这三个页面、契约里有这三条端点，而**后端一行代码都没有** ——
 * 商家能开店、能上架、能收到单，然后就卡住了。
 *
 * <p>用例覆盖三类失败，它们才是真实世界里最常发生的：
 * 查别家的单、不带快递单号发货、对同一单重复发货。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizOrderFulfillFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    /** 与 M9aOpsFlowTest 同一个：stub 回调的签名，配在 application-test.yml */
    private static final String STUB_SECRET = "stub-secret";

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 发货：带快递单号推进到履约中，并留痕")
    void shipMovesToFulfilling() throws Exception {
        Ctx c = prepare("12600129001", "发货测试店", "12600129002");

        mvc().perform(post("/biz/order/" + c.subOrderNo + "/ship")
                        .header("Authorization", "Bearer " + c.merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expressNo\":\"SF1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("FULFILLING"));

        mvc().perform(get("/biz/order/" + c.subOrderNo)
                        .header("Authorization", "Bearer " + c.merchantToken))
                .andExpect(jsonPath("$.data.status").value("FULFILLING"));
    }

    @Test
    @DisplayName("★ 不带快递单号不许发货 —— 没有单号的「已发货」对买家没用")
    void shipRequiresExpressNo() throws Exception {
        Ctx c = prepare("12600129003", "空单号测试店", "12600129004");

        mvc().perform(post("/biz/order/" + c.subOrderNo + "/ship")
                        .header("Authorization", "Bearer " + c.merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expressNo\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★ 同单号重复发货是空操作；换单号 = 改单号，允许但要留痕")
    void reshipSemantics() throws Exception {
        Ctx c = prepare("12600129005", "重复发货店", "12600129006");

        ship(c, "SF999").andExpect(jsonPath("$.code").value(0));
        // 重复点击是常态（网络慢时人会连点两下），不该报错
        ship(c, "SF999").andExpect(jsonPath("$.code").value(0));

        /*
         * 换单号：**允许**。填错单号必须能改，拒了商家只能打客服。
         * 但它会改掉买家看到的物流号，所以要留痕 —— 见时间线里那条「商家改快递单号」。
         */
        ship(c, "YT123").andExpect(jsonPath("$.data.status").value("FULFILLING"));

        String detail = mvc().perform(get("/biz/order/" + c.subOrderNo)
                        .header("Authorization", "Bearer " + c.merchantToken))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("YT123");
    }

    private org.springframework.test.web.servlet.ResultActions ship(Ctx c, String expressNo)
            throws Exception {
        return mvc().perform(post("/biz/order/" + c.subOrderNo + "/ship")
                .header("Authorization", "Bearer " + c.merchantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expressNo\":\"" + expressNo + "\"}"));
    }

    @Test
    @DisplayName("★ 标记送达：履约中 → 已完成")
    void deliveredCompletesOrder() throws Exception {
        Ctx c = prepare("12600129007", "送达测试店", "12600129008");
        mvc().perform(post("/biz/order/" + c.subOrderNo + "/ship")
                .header("Authorization", "Bearer " + c.merchantToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expressNo\":\"SF888\"}"));

        mvc().perform(post("/biz/order/" + c.subOrderNo + "/delivered")
                        .header("Authorization", "Bearer " + c.merchantToken))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("★ 查别家的单返回「不存在」而不是「无权限」—— 后者是个订单探测器")
    void foreignOrderIsNotFound() throws Exception {
        Ctx mine = prepare("12600129009", "本店", "12600129010");
        Ctx other = prepare("12600129011", "别家店", "12600129012");

        /*
         * 返回 403 等于确认「这个单号是真的」，而单号可枚举 ——
         * 拿一个订单号段扫一遍就能知道别家哪天有多少单。
         */
        mvc().perform(get("/biz/order/" + other.subOrderNo)
                        .header("Authorization", "Bearer " + mine.merchantToken))
                // 10404 是本系统「数据不存在」的业务码；关键是它不是 403
                .andExpect(jsonPath("$.code").value(10404));
    }

    // ------------------------------------------------------------------ 辅助

    private record Ctx(String merchantToken, String subOrderNo) {
    }

    /** 开一家店、上一件货、让买家下一单，返回商家令牌与子单号。 */
    private Ctx prepare(String merchantPhone, String shopName, String buyerPhone) throws Exception {
        String user = login(merchantPhone);
        String applyNo = json.readTree(mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + shopName + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("applyNo").asString();

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        String token = login(merchantPhone);

        String goodsNo = json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"发货测试商品\",\"subtitle\":\"测试\",\"type\":\"NORMAL\","
                                + "\"cover\":\"📦\",\"images\":[],\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();

        String ops = opsLogin("goods", "goods123");
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit").header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));

        String buyer = login(buyerPhone);
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();
        String payOrderNo = json.readTree(mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"EXPRESS\",\"addressId\":null,"
                                + "\"items\":[{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                                + "\",\"qty\":1}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("payOrderNo").asString();
        /*
         * **走支付回调而不是 /pay**：`/pay` 只是拿收银台参数，
         * 真正把单推到 WAIT_FULFILL 的是通道回调 —— 只调 /pay 的话单还在 WAIT_PAY，
         * 发货会被状态机拒，而那是测试写错了不是代码错了。
         */
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-"
                                + payOrderNo + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                // 回调面向支付通道，返回的**不是**统一信封 —— 断 HTTP 状态即可
                .andExpect(status().isOk());

        String list = mvc().perform(get("/biz/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        var records = json.readTree(list).get("data").get("records");
        assertThat(records.size()).as("商家应当看得到刚下的单").isGreaterThan(0);
        /*
         * 商家视角的 OrderVO.orderNo 装的是**子单号**（见 orderView：`s.getSubOrderNo()`）——
         * 商家谈的一直是自己那一单，主单是买家的一次支付，与他无关。
         */
        return new Ctx(token, records.get(0).get("orderNo").asString());
    }

    private String login(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
