package ai.neargo.shop.scenario;

import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.service.OrderService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ★★★ 超时关单撞上付款回调（TDD-订单状态机 AC13）。
 *
 * <p><b>判据是「有没有释放」，不是状态</b>：旧写法拿旧对象 {@code updateById}，会被 {@code @Version} 乐观锁
 * 静默拒掉（写 0 行）—— 所以状态本来就写不回 CLOSED，只看状态的断言对旧写法也是绿的。
 * 旧写法真正的危害在后面：照样释放库存 / 券 / 活动配额 / 积分。这里用 spy 盯住券的 release。
 *
 * <p>另开一个库：@MockitoSpyBean 会让本类拿到新上下文，同库重跑种子会起不来（见 PayScenePassedThroughTest）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:close-race;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@ActiveProfiles("test")
@DisplayName("超时关单撞上付款")
class CloseRaceReleaseTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private CouponPort couponPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 关单拿着旧数据、付款刚落库：关单什么都不做 —— 不改状态、不取消订单、不释放券")
    void closeLosesToPayment() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400152001");
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"))
                .andExpect(status().isOk());
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "close-race").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String payNo = json.readTree(body).get("data").get("payOrderNo").asString();

        // 关单任务先读到了 WAIT_PAY 的那一行（旧数据）……
        OrdOrder stale = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo, payNo));
        // ……付款回调在这一刻落库
        mvc().perform(post("/mp/order/" + payNo + "/pay").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payNo + "\",\"transactionId\":\"tx-close-race\",\"sign\":\"stub-secret\"}"))
                .andExpect(status().isOk());
        assertThat(paymentStatus(payNo)).isEqualTo("PAID");

        // 关单接着拿旧数据关（closeOne 是包级方法，按真实入口的写法反射调用）
        Object target = org.springframework.aop.support.AopUtils.isAopProxy(orderService)
                ? ((org.springframework.aop.framework.Advised) orderService).getTargetSource().getTarget()
                : orderService;
        var m = target.getClass().getDeclaredMethod("closeOne", OrdOrder.class, String.class);
        m.setAccessible(true);
        m.invoke(target, stale, "支付超时");

        verify(couponPort, never()).release(anyString());
        assertThat(paymentStatus(payNo)).isEqualTo("PAID");
        assertThat(jdbc.queryForList("select status from ord_sub_order where order_no = ?", String.class, payNo))
                .doesNotContain("CANCELLED");
    }

    private String paymentStatus(String payNo) {
        return jdbc.queryForObject("select status from ord_order where order_no = ?", String.class, payNo);
    }
}
