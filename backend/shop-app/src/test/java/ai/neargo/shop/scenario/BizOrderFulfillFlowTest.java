package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import tools.jackson.databind.JsonNode;
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
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


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
                /*
                 * 发货后的状态是 **FULFILLING**，不是 SHIPPED。
                 *
                 * `SHIPPED`（已发货）与 `ARRIVED`（已到自提点）曾是两个状态常量，
                 * 后来被删掉了 —— 见 `OrderStatusView` 的类注释：它们是
                 * 「状态 × 履约方式」那个乘法的**结果**，不是状态本身。
                 * 这个方法自己的名字（shipMovesToFulfilling）早就跟上了，只有断言没跟上。
                 */
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
        // 同上：状态是 FULFILLING，SHIPPED 已不是状态常量
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
        // A7：这个令牌要打 /biz/**，必须是 btk_
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, merchantPhone);

        String goodsNo = json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"发货测试商品\",\"subtitle\":\"测试\",\"type\":\"NORMAL\","
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
        /*
         * 买家先绑社区：下单时社区会固化到主单上，而**运营按社区做数据域隔离**。
         * 不绑的话订单没有社区，平台端按社区筛出来永远是空的 ——
         * 而列表本身是好的，看起来只是「这个社区没单」。
         */
        mvc().perform(post("/mp/user/community").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0001\"}"));
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();
        /*
         * 快递单**必须有收货地址**（70014，2026-08-15 立的闸）。此前这里传
         * `addressId:null`，闸立起来之后这条链路一直是红的 —— 是夹具没跟上，不是闸错了
         * （闸只在 SHIPPED_FULFILLMENTS 时触发，自提单不受影响）。
         *
         * **收货人电话用 13x 而不是 buyerPhone**：测试登录号一律走 126 前缀
         *（约定俗成的「一眼假」号段），而 126 不是大陆号段，`Phones.CN_MOBILE` 会拒。
         * 收货人电话与账号手机号本来就是两个字段。
         */
        String addressId = json.readTree(mvc().perform(post("/mp/user/address")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"买家\",\"phone\":\"13600180013\",\"province\":\"浙江省\","
                                + "\"city\":\"杭州市\",\"district\":\"西湖区\",\"detail\":\"文三路 1 号\","
                                + "\"isDefault\":true,\"tag\":\"家\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                // 这个接口返回的是**整份地址列表**，不是刚存的那一条；买家只有这一条
                .get("data").get(0).get("addressId").asString();
        String payOrderNo = json.readTree(mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"EXPRESS\",\"addressId\":\"" + addressId + "\","
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
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
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
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    // ---------------------------------------------------------------- 端上标签页（展示状态）

    /*
     * 这两条守的是一个**用户直接可见**的故障：
     * b-app 的标签页把 PAID / SHIPPED / ARRIVED 当查询参数发过来，
     * 而库里存的是 WAIT_FULFILL / FULFILLING —— 曾经直接拿去比库状态，
     * 于是「待发货」「已发货」「待核销」三个页签**永远是空的**，
     * 而「全部」是好的，所以看起来只是几个页签没数据，没人往契约上想。
     */

    @Test
    @DisplayName("★ b-app「待发货」标签页筛得出刚付款的单")
    void toShipTabFindsPaidOrders() throws Exception {
        Ctx c = prepare("13400134201", "页签测试·待发货", "13410134201");

        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + c.merchantToken())
                        .param("status", "PAID"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total")
                        .value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("★ 发货后「已发货」筛得出、「待发货」筛不出 —— 页签之间不能重叠")
    void shippedTabMovesTheOrder() throws Exception {
        Ctx c = prepare("13400134202", "页签测试·已发货", "13410134202");
        ship(c, "SF-TAB-1");

        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + c.merchantToken())
                        .param("status", "SHIPPED"))
                .andExpect(jsonPath("$.data.total")
                        .value(org.hamcrest.Matchers.greaterThan(0)));
        mvc().perform(get("/biz/order").header("Authorization", "Bearer " + c.merchantToken())
                        .param("status", "PAID"))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ---------------------------------------------------------------- 平台端订单（P-4.1）

    @Test
    @DisplayName("★ 平台侧订单列表是跨商家的 —— 不解除数据域的话运营看到的恒为空")
    void opsListSeesAcrossMerchants() throws Exception {
        Ctx a = prepare("13400135001", "平台订单·甲店", "13410135001");
        prepare("13400135002", "平台订单·乙店", "13410135002");
        String ops = opsLogin("support", "support123");

        String body = mvc().perform(get("/ops/orders").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("total").asInt()).isGreaterThanOrEqualTo(2);

        // 详情与兄弟单
        mvc().perform(get("/ops/orders/" + a.subOrderNo()).header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.data.orderNo").value(a.subOrderNo()))
                // 社区在主单上，子单没有 —— 平台侧要 join 出来，否则运营按社区筛不了
                .andExpect(jsonPath("$.data.communityNo").exists());
    }

    @Test
    @DisplayName("★ 干预必须写原因 —— 没有原因的改状态事后无法复盘")
    void interveneNeedsRemark() throws Exception {
        Ctx c = prepare("13400135010", "平台订单·干预", "13410135010");
        String ops = opsLogin("support", "support123");

        mvc().perform(post("/ops/orders/" + c.subOrderNo() + "/intervene")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"COMPLETED\",\"remark\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("★ 迁移由后端状态机判定，端上那份迁移表不是权威")
    void backendStateMachineIsTheAuthority() throws Exception {
        Ctx c = prepare("13400135020", "平台订单·非法迁移", "13410135020");
        String ops = opsLogin("support", "support123");

        // 刚付款的单直接改成「已发货」在库里是 WAIT_FULFILL → FULFILLING，允许；
        // 但改回 WAIT_PAY 是倒流，状态机必须拒
        mvc().perform(post("/ops/orders/" + c.subOrderNo() + "/intervene")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"WAIT_PAY\",\"remark\":\"想退回去\"}"))
                .andExpect(jsonPath("$.code").value(20004));
    }

    @Test
    @DisplayName("干预留痕能查回来，且带着「从哪到哪、谁干的」")
    void interventionIsRecorded() throws Exception {
        Ctx c = prepare("13400135030", "平台订单·留痕", "13410135030");
        String ops = opsLogin("support", "support123");

        mvc().perform(post("/ops/orders/" + c.subOrderNo() + "/intervene")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"COMPLETED\",\"remark\":\"用户已确认收到\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        String body = mvc().perform(get("/ops/orders/" + c.subOrderNo() + "/interventions")
                        .header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).get("data");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("remark").asString()).contains("用户已确认收到");
    }

    @Test
    @DisplayName("★ 异常队列是实时视图 —— 单子一推进就不在里面了")
    void exceptionQueueIsALiveView() throws Exception {
        String ops = opsLogin("support", "support123");
        // 终态的单永远不该进队列（「已完成」没有「卡住」这回事）
        String body = mvc().perform(get("/ops/orders/exceptions").header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode e : json.readTree(body).get("data").get("records")) {
            assertThat(e.get("order").get("status").asString())
                    .isNotIn("COMPLETED", "CANCELLED", "REFUNDED");
            // 界面要能说明「为什么它算异常」，所以阈值必须一起下发
            assertThat(e.get("thresholdMinutes").asLong()).isPositive();
        }
    }

    @Test
    @DisplayName("看单与改单是两件事：没有 order:intervene 的角色改不了状态")
    void viewingIsNotIntervening() throws Exception {
        Ctx c = prepare("13400135040", "平台订单·权限", "13410135040");
        String bd = opsLogin("bd", "bd123");

        mvc().perform(get("/ops/orders").header("Authorization", "Bearer " + bd))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/ops/orders/" + c.subOrderNo() + "/intervene")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"COMPLETED\",\"remark\":\"我来改\"}"))
                .andExpect(jsonPath("$.code").value(10403));
    }
}
