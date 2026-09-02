package ai.neargo.shop.scenario;

import ai.neargo.shop.marketing.attribution.entity.MktAttribution;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 代客下单（P-4.1.4）：老人打电话来，客服替他把单下了。
 *
 * <p>需求梳理见 {@code docs/requirements/代客下单-需求梳理.md}。这里每条用例盯的都是
 * <b>「这单到底是不是那位顾客的」</b> —— 运营端 mock 原先收的是一个自由文本昵称，
 * 照它做出来的会是一张没有主人的订单：顾客在 C 端看不到、付不了款、也退不了。
 * 而那种错在运营端界面上<b>完全看不出来</b>：列表里躺着一张单，看着一切正常。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProxyOrderFlowTest {

    private static final String CUSTOMER_PHONE = "12900129088";
    private static final String MERCHANT = "M0001";
    private static final String SKU = "SK0001";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper subOrderMapper;
    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper orderMapper;
    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper statusLogMapper;
    @Autowired
    private BaseMapper<MktAttribution> attributionMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 归因是**跨用例共享**的一行（一人一条有效）—— 种了就要还原，否则别的用例的佣金档会被我改掉。 */
    @AfterEach
    void cleanup() throws Exception {
        attributionMapper.delete(Wrappers.<MktAttribution>lambdaQuery()
                .likeRight(MktAttribution::getUserNo, "U"));
    }

    @Test
    @DisplayName("★★★ 代客下的单落在**顾客**名下：他在自己的订单列表里看得到、付得了")
    void proxyOrderLandsOnTheCustomer() throws Exception {
        String customer = login(CUSTOMER_PHONE);
        String userNo = userNoOf(customer);
        String ops = opsLogin();

        String body = proxy(ops, req(userNo, "ONLINE", SKU, 2, "老人电话下单，家里米吃完了"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String orderNo = json.readTree(body).get("data").get("orderNo").asString();

        // ★ 顾客自己的订单列表里要有它 —— 这一条挂了，说明这单没有主人
        String mine = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mine).as("★ 代客下的单不在顾客的订单列表里 —— 他看不到、也付不了").contains(orderNo);

        var sub = subOf(orderNo);
        assertThat(sub.getUserNo()).as("★ 订单挂在了别人名下").isEqualTo(userNo);
        assertThat(sub.getStatus()).isEqualTo(OrdSubOrder.WAIT_PAY);
        // 金额一律服务端算：端上只传了 skuNo 与数量
        assertThat(sub.getPayAmount()).isNotNull().isGreaterThan(0L);
    }

    /**
     * <b>归因照常按顾客解析</b>，不因为「客服代下」就改成平台流量。
     *
     * <p>运营端 mock 里是硬写的 {@code PLATFORM}，而 trafficSource 决定佣金档 ——
     * 那样商家自己扫码带来的客人打个电话下单，就变成**商家为自己的客人付平台档佣金**，
     * 而不会有任何人发现。
     */
    @Test
    @DisplayName("★★★ 归因按顾客算：商家自己带来的客人，代下的单仍是 MERCHANT_OWNED")
    void proxyOrderKeepsCustomerAttribution() throws Exception {
        String customer = login(CUSTOMER_PHONE);
        String userNo = userNoOf(customer);
        seedStoreCodeAttribution(userNo, MERCHANT);
        String ops = opsLogin();

        String body = proxy(ops, req(userNo, "ONLINE", SKU, 1, "顾客扫过这家的店铺码，电话来订"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(subOf(json.readTree(body).get("data").get("orderNo").asString()).getTrafficSource())
                .as("★ 代客下单把商家自带的客流改成了平台流量 —— 商家要为自己的客人多付佣金")
                .isEqualTo("MERCHANT_OWNED");
    }

    /**
     * <b>顾客与商家都要看得出「这单是代下的」。</b>只写审计日志不够 ——
     * 那张表只有运营看得到，而这句话正是顾客打电话来问、商家备货时要看到的第一句。
     */
    @Test
    @DisplayName("★★ 订单时间线上留下「代客下单 + 原因 + 谁下的」")
    void proxyOrderIsVisibleOnTheTimeline() throws Exception {
        String customer = login(CUSTOMER_PHONE);
        String ops = opsLogin();
        String body = proxy(ops, req(userNoOf(customer), "ONLINE", SKU, 1, "腿脚不便，来电代下"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        var logs = statusLogMapper.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                .eq(OrdStatusLog::getSubOrderNo,
                        subOf(json.readTree(body).get("data").get("orderNo").asString()).getSubOrderNo()));
        assertThat(logs).anySatisfy(l -> {
            assertThat(l.getLabel()).contains("代客下单").contains("腿脚不便");
            assertThat(l.getOperatorType()).isEqualTo(OrdStatusLog.BY_PLATFORM);
            assertThat(l.getOperatorNo()).as("没记下是谁代下的").isNotBlank();
        });
    }

    /**
     * 四条硬校验。**每一条被放过的后果都不是报错，而是一张说不清的单**：
     * 没有主人的单、顾客要多付一次的单、客服替他花掉的券、送不到的地址。
     */
    @Test
    @DisplayName("★★★ 没有账号 / 空原因 / 跨商家 / 要地址的履约方式，一条都下不了")
    void proxyOrderRefusesWhatItCannotExplain() throws Exception {
        String customer = login(CUSTOMER_PHONE);
        String userNo = userNoOf(customer);
        String ops = opsLogin();
        long before = orderCount();

        // 没绑账号：人档里有号但没 user —— 这单会没有主人
        proxy(ops, req("U-NOT-EXIST", "ONLINE", SKU, 1, "随便写点"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        // 空原因：代客绕过了用户自主下单，得留下为什么
        proxy(ops, req(userNo, "ONLINE", SKU, 1, "   "))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        // 跨商家：全站按商家拆单，一次一个商家
        proxy(ops, body(userNo, "M0002", "ONLINE", "STORE_PICKUP", SKU, 1, "跨商家"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        /*
         * 快递要收货地址，而客服不该替顾客填地址（也没法核对）。
         *
         * ★ 断言的是 **10400（这条路不给走）而不是「非 0」**：下单主干里那道
         * 「快递必须有地址」的校验（RECEIVER_REQUIRED 70014）本来就会拒掉它 ——
         * 只断言「非 0」的话，我这道闸删掉了测试照样绿，而客服看到的会变成
         * 一句「请选择收货地址」，然后他会去找那个根本不存在的地址输入框。
         */
        proxy(ops, body(userNo, MERCHANT, "ONLINE", "EXPRESS", SKU, 1, "寄到家里"))
                .andExpect(jsonPath("$.code").value(10400));

        assertThat(orderCount()).as("★ 被拒的请求却落了单").isEqualTo(before);
    }

    /**
     * <b>手一抖不能变成两单。</b>两单会真的锁两份库存、也真的要顾客付两次；
     * 而客服看到的只是「刚才好像没反应」。
     */
    @Test
    @DisplayName("★★ 同一个幂等键提交两次只有一单")
    void sameIdempotencyKeyCreatesOneOrder() throws Exception {
        String customer = login(CUSTOMER_PHONE);
        String ops = opsLogin();
        String key = "PROXY-" + java.util.UUID.randomUUID();
        String payload = req(userNoOf(customer), "ONLINE", SKU, 1, "连点了两下");

        String first = proxyWithKey(ops, payload, key).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String second = proxyWithKey(ops, payload, key).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second).get("data").get("orderNo").asString())
                .as("★ 同一个幂等键下出了两张单")
                .isEqualTo(json.readTree(first).get("data").get("orderNo").asString());
    }

    /**
     * <b>没装过 App 的人也能电话下单</b>（2026-09-03 产品决定）。
     *
     * <p>关键不在「能建号」，而在**建的是不是他日后登录会命中的那个号** ——
     * 另起一套建户逻辑的话，客服建的号与他自己登出来的号是两个人，
     * 那张单他永远看不到，而两边都不会报错。
     */
    @Test
    @DisplayName("★★★ 只有手机号也能代下：建的号就是他日后登录命中的那个号")
    void proxyOrderProvisionsAccountForANewPhone() throws Exception {
        String phone = "129001291" + (10 + new java.util.Random().nextInt(80));
        String ops = opsLogin();

        String body = mvc().perform(post("/ops/orders/proxy")
                        .header("Authorization", "Bearer " + ops)
                        .header("Idempotency-Key", "PROXY-" + java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "phone", phone, "merchantNo", MERCHANT, "payMode", "ONLINE",
                                "fulfillment", "STORE_PICKUP", "pickupNo", "PP0001",
                                "items", java.util.List.of(java.util.Map.of("skuNo", SKU, "qty", 1)),
                                "reason", "老人没装小程序，电话来订"))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String orderNo = json.readTree(body).get("data").get("orderNo").asString();

        // ★ 他自己用这个号登录 —— 必须看得到那张单
        String token = login(phone);
        String mine = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mine)
                .as("★ 客服建的号与他登录命中的号不是同一个 —— 这张单他永远看不到")
                .contains(orderNo);
        assertThat(subOf(orderNo).getUserNo()).isEqualTo(userNoOf(token));
    }

    /**
     * <b>线上代客单给 30 分钟</b>（2026-09-03 产品决定）。
     *
     * <p>平台通用时限（默认 15 分钟）是给「人正看着屏幕」那条路配的，
     * 而电话下单的人要先挂电话、打开小程序、找到订单才付得上 ——
     * 用通用值的话他多半在还没找到那张单的时候就被关掉了。
     */
    @Test
    @DisplayName("★★ 线上代客单的支付时限是 30 分钟，不是平台通用的那个数")
    void onlineProxyOrderGetsHalfAnHour() throws Exception {
        String customer = login(CUSTOMER_PHONE);
        String ops = opsLogin();
        long before = System.currentTimeMillis();

        String body = proxy(ops, req(userNoOf(customer), "ONLINE", SKU, 1, "电话来订，回头自己付"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        Long deadline = orderMapper.selectOne(Wrappers.<ai.neargo.shop.trade.entity.OrdOrder>lambdaQuery()
                        .eq(ai.neargo.shop.trade.entity.OrdOrder::getOrderNo,
                                subOf(json.readTree(body).get("data").get("orderNo").asString()).getOrderNo())
                        .last("limit 1"))
                .getPayDeadlineAt();
        assertThat(deadline).isNotNull();
        long minutes = (deadline - before) / 60_000L;
        assertThat(minutes)
                .as("★ 代客单用了平台通用时限 —— 老人还没找到那张单就被关掉了")
                .isBetween(28L, 31L);
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions proxy(String ops, String payload) throws Exception {
        return proxyWithKey(ops, payload, "PROXY-" + java.util.UUID.randomUUID());
    }

    private ResultActions proxyWithKey(String ops, String payload, String key) throws Exception {
        return mvc().perform(post("/ops/orders/proxy")
                .header("Authorization", "Bearer " + ops)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    private String req(String userNo, String payMode, String skuNo, int qty, String reason) {
        return body(userNo, MERCHANT, payMode, "STORE_PICKUP", skuNo, qty, reason);
    }

    private String body(String userNo, String merchantNo, String payMode, String fulfillment,
                        String skuNo, int qty, String reason) {
        return json.writeValueAsString(java.util.Map.of(
                "userNo", userNo, "merchantNo", merchantNo, "payMode", payMode,
                "fulfillment", fulfillment, "pickupNo", "PP0001",
                "items", java.util.List.of(java.util.Map.of("skuNo", skuNo, "qty", qty)),
                "reason", reason));
    }

    private OrdSubOrder subOf(String orderNo) {
        return subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getOrderNo, orderNo).last("limit 1"));
    }

    private long orderCount() {
        return subOrderMapper.selectCount(Wrappers.emptyWrapper());
    }

    /** 店铺码归因 = 商家自带客流（ADR-004 §6）。种一行，跑完在 {@link #cleanup} 里删掉。 */
    private void seedStoreCodeAttribution(String userNo, String merchantNo) {
        attributionMapper.delete(Wrappers.<MktAttribution>lambdaQuery()
                .eq(MktAttribution::getUserNo, userNo));
        MktAttribution row = new MktAttribution();
        row.setUserNo(userNo);
        row.setEntityNo(merchantNo);
        row.setSource(MktAttribution.STORE_CODE);
        row.setExpireAt(System.currentTimeMillis() + 30L * 86_400_000L);
        row.setTenantNo("MAIN");
        attributionMapper.insert(row);
    }

    private String userNoOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
