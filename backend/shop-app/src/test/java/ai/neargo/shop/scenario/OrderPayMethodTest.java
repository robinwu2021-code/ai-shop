package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 收银台的支付方式列表（C-1）。
 *
 * <h2>这组守的是一对<b>相反的空</b></h2>
 * 列表为空有两种含义，端上要做相反的事：
 * <ul>
 *   <li>{@code configured = false} —— 商家进件还没走完，
 *       <b>照常允许支付</b>（钱先欠着，与下单时同一口径）；</li>
 *   <li>{@code configured = true} 而列表空 —— 真的一种都不支持，<b>要拦住</b>。</li>
 * </ul>
 * 合成一个空数组的话，<b>一个完全正常的订单会被端上拦死</b> ——
 * 而这个错要在浏览器里跑真实数据时才现形，单测与类型都拦不住。
 *
 * <p>走 HTTP 而不是直接调 service：这样连端点路径、权限、
 * JSON 序列化一起验了 —— 而「service 对了而端点没挂上」正是
 * 这条链上出现过的一类问题。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderPayMethodTest {

    private static final String PHONE = "13700137000";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TradeMappers.OrderMapper orderMapper;
    @Autowired
    private TradeMappers.SubOrderMapper subOrderMapper;
    @Autowired
    private MerchantMappers.MchPaymentMapper paymentMapper;

    /** 判据来自路由表：装配了才算接得上，与通道表的 enabled 位是两件事 */
    @Autowired
    private ai.neargo.shop.pay.channel.PayGatewayRouter router;

    private static int seq = 0;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /** 造一笔待支付订单，挂在指定商家下。userNo 取登录后的当前用户 */
    private String order(String userNo, String entityNo) {
        String orderNo = "OD-PM-" + (++seq);
        DataScopeContext.executeWithoutScope(() -> {
            OrdOrder o = new OrdOrder();
            o.setOrderNo(orderNo);
            o.setUserNo(userNo);
            o.setStatus(OrdOrder.WAIT_PAY);
            o.setPayAmount(9_900L);
            orderMapper.insert(o);
            OrdSubOrder sub = new OrdSubOrder();
            sub.setSubOrderNo("SUB-" + orderNo);
            sub.setOrderNo(orderNo);
            sub.setUserNo(userNo);
            sub.setEntityNo(entityNo);
            sub.setStatus(OrdOrder.WAIT_PAY);
            sub.setPayAmount(9_900L);
            subOrderMapper.insert(sub);
            return null;
        });
        return orderNo;
    }

    private JsonNode payMethods(String token, String orderNo) throws Exception {
        String body = mvc().perform(get("/mp/order/" + orderNo + "/pay-method")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    /** 登录后拿当前用户号 —— 订单的 user_no 要与它一致，否则 requireOwnOrder 查不到 */
    private String currentUserNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode d = json.readTree(body).get("data");
        return d == null ? null : d.path("userNo").asText(null);
    }

    @Test
    @DisplayName("★★★ 商家没进件：列表空但 configured=false —— 端上照常放行，不是拦住")
    void notConfiguredIsNotUnsupported() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, PHONE);
        String userNo = currentUserNo(token);
        assertThat(userNo).as("拿不到当前用户号，下面的订单归属就对不上").isNotBlank();

        String orderNo = order(userNo, "M-PM-NEVER-" + seq);
        JsonNode vo = payMethods(token, orderNo);

        assertThat(vo).as("端点没挂上？返回里没有 data").isNotNull();
        assertThat(vo.path("configured").asBoolean(true))
                .as("一家都没配过支付方式时 configured 必须是 false —— "
                        + "端上据此照常允许支付（钱先欠着）。写成 true 的话，"
                        + "一个完全正常的订单会被端上拦死")
                .isFalse();
    }

    @Test
    @DisplayName("★★ 商家进过件：configured=true，且有一种能用")
    void configuredMerchantHasUsableMethod() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, PHONE);
        String userNo = currentUserNo(token);
        String entity = "M-PM-OK-" + (++seq);

        MchPaymentMerchant pm = new MchPaymentMerchant();
        pm.setEntityNo(entity);
        pm.setPayChannel("TEST");
        pm.setPayMerchantNo("PMN-" + seq);
        pm.setApplyStatus(MchPaymentMerchant.ACTIVE);
        pm.setPayMethods("[\"TEST\"]");
        DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(pm));

        JsonNode vo = payMethods(token, order(userNo, entity));

        assertThat(vo.path("configured").asBoolean()).isTrue();
        assertThat(vo.path("methods")).isNotEmpty();
        boolean anyUsable = false;
        for (JsonNode m : vo.path("methods")) {
            anyUsable = anyUsable || m.path("available").asBoolean();
        }
        assertThat(anyUsable).as("进过件的商家应当有一种能用的支付方式").isTrue();
    }

    @Test
    @DisplayName("★★ 不可用的也要返回并带原因 —— 过滤掉的话客服答不出「为什么我没有」")
    void unavailableMethodsCarryReason() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, PHONE);
        String userNo = currentUserNo(token);
        JsonNode vo = payMethods(token, order(userNo, "M-PM-NONE-" + (++seq)));

        for (JsonNode m : vo.path("methods")) {
            if (!m.path("available").asBoolean()) {
                assertThat(m.path("unavailableReason").asText(""))
                        .as("不可用却没给原因 —— 用户只会看到一个灰掉的按钮，"
                                + "而客服也说不出为什么")
                        .isNotBlank();
            }
        }
        // 对照量：至少要返回了一些方式，否则上面的循环一次都没跑
        assertThat(vo.path("methods")).as("一种方式都没返回，上面那个循环等于没跑").isNotEmpty();
    }

    @Test
    @DisplayName("★★★ 网关没装配的通道，available 必须为 false —— 端上会默认选中第一个可用的")
    void channelsWithoutAGatewayAreNeverAvailable() throws Exception {
        /*
         * **这条钉的是端上「默认选中」的前提。**
         *
         * 收银台的默认选择是 `list.methods.find(m => m.available)` ——
         * 只要有一个网关没接的通道被标成可用，用户打开页面**默认就选中它**，
         * 点「立即支付」必然失败，而失败信息是「收款通道还没接通」，
         * 看起来像商家没进件。
         *
         * 判据来自路由表（{@code PayGatewayRouter.supports}）而不是通道表的
         * enabled 位：enabled 说的是「运营开没开这条」，
         * 装配说的是「代码接没接这条」，**两者可以不一致，而不一致的那一侧就是坑**。
         * 2026-09-04 线上正是这个状态：支付宝 enabled=1 而网关没接。
         */
        String token = TestLogin.consumer(mvc(), json, otpStore, PHONE);
        String userNo = currentUserNo(token);
        JsonNode vo = payMethods(token, order(userNo, "M-PM-GW-" + (++seq)));

        int checked = 0;
        for (JsonNode m : vo.path("methods")) {
            String ch = m.path("payChannel").asText();
            if (!router.supports(ch)) {
                assertThat(m.path("available").asBoolean())
                        .as("通道 %s 没有装配网关，却被标成可用 —— "
                                + "端上默认会选中第一个可用的，用户点了必然失败", ch)
                        .isFalse();
                checked++;
            }
        }
        /*
         * **对照量。** 一个没装配的通道都没有的话，上面的循环一次都没跑，
         * 而这条用例会「全绿地什么都没验」。
         */
        assertThat(checked)
                .as("测试世界里所有通道都装配了网关 —— 这条用例没有验到任何东西，"
                        + "要么补一条没网关的通道种子，要么删掉它")
                .isPositive();
    }
}
