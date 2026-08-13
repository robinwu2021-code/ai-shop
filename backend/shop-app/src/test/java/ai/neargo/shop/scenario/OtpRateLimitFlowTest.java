package ai.neargo.shop.scenario;

import ai.neargo.shop.common.captcha.CaptchaService;
import ai.neargo.shop.common.ratelimit.RateLimiter;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 发码限流三道闸 —— **唯一一处真的把闸打开的地方**。
 *
 * <p>整套用例在 {@code testcfg} 里关着限流（登录是几乎每条旅程的第一步，
 * 反复给同一号码发码会被 60 秒间隔闸全部拦掉）。所以这个类用
 * {@code properties} 单独把它打开，并把阈值调小到能在一个测试里撞到。
 *
 * <p><b>它守的是一个花钱的洞</b>：{@code /mp/user/otp/send} 是公网未鉴权端点，
 * 接上真实短信通道后每次调用都是钱。这几条断言红了，就意味着任何人
 * 循环调它就能烧掉平台的短信费。
 */
@SpringBootTest(properties = {
        "shop.otp.rate-limit=true",
        "shop.otp.interval-seconds=60",
        // 日上限调到 3，才能在一个用例里撞到；生产是 10
        "shop.otp.daily-per-phone=3",
        "shop.otp.hourly-per-ip=5",
})
@ActiveProfiles("test")
@DisplayName("发码限流三道闸")
class OtpRateLimitFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RateLimiter limiter;

    @Autowired
    private CaptchaService captcha;

    /**
     * **必须 apply(springSecurity())**：IP 是由 {@code ConsumerTokenAuthFilter} 放进
     * ThreadLocal 的，不装安全链那个过滤器根本不跑 —— 于是按 IP 那道闸拿不到 IP 而放行，
     * 测试会绿得毫无意义。第一版就是这么写的，`perIpCapIsEnforced` 当场变红把它抓了出来。
     */
    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /**
     * **限流器是进程内单例，跨用例共享**。手机号可以每条用例换一个来隔离，
     * 但 IP 全都是 127.0.0.1 —— 前几条用例发出去的码会把 IP 额度吃掉，
     * 于是「按 IP 那道」在轮到它自己的用例时早就满了，报的还是同一个码，
     * 看着像实现错了。每条用例开始前清一次。
     */
    @org.junit.jupiter.api.BeforeEach
    void resetIpQuota() {
        limiter.reset("otp:ip:127.0.0.1");
    }

    /** 每条用例换一个号，免得互相踩到对方的计数 */
    private static String phone(String seed) {
        return "1390000" + seed;
    }

    private void send(String phone, org.springframework.test.web.servlet.ResultMatcher expect) throws Exception {
        mvc().perform(post("/mp/user/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(expect);
    }

    @Test
    @DisplayName("★★★ 同一手机号 60 秒内第二次发码被拒（10450），且带「还要等几秒」")
    void secondSendWithinIntervalIsRejected() throws Exception {
        String p = phone("1001");
        send(p, jsonPath("$.code").value(0));
        // 第二次立刻发 —— 60 秒间隔闸应当挡下
        mvc().perform(post("/mp/user/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + p + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10450))
                // 秒数进了文案，端上据此做倒计时按钮
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("秒")));
    }

    @Test
    @DisplayName("★★★ 同一手机号当日超过上限后拒发（10451）—— 与间隔闸分开的码，端上不该显示倒计时")
    void dailyCapIsEnforced() throws Exception {
        String p = phone("2002");
        // 绕开间隔闸：每次发完把间隔那把 key 清掉，只留日计数
        for (int i = 0; i < 3; i++) {
            send(p, jsonPath("$.code").value(0));
            limiter.reset("otp:interval:" + p);
        }
        mvc().perform(post("/mp/user/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + p + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10451));
    }

    @Test
    @DisplayName("★★ 同 IP 每小时超限后拒发 —— 换号刷码的机器人只会撞这一道")
    void perIpCapIsEnforced() throws Exception {
        // 每次换一个号，前两道（按号）都不会触发；能挡住的只有按 IP 那道
        for (int i = 0; i < 5; i++) {
            send(phone("30" + (10 + i)), jsonPath("$.code").value(0));
        }
        mvc().perform(post("/mp/user/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone("3099") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10429));
    }

    @Test
    @DisplayName("★★★ 图形验证码一次性消费 —— 留着的话一次挑战能被暴力猜 32^4 次")
    void captchaIsConsumedOnce() {
        CaptchaService.Challenge c = captcha.issue();
        assertThat(c.captchaId()).isNotBlank();
        assertThat(c.imageBase64()).isNotBlank();

        // 错误输入也要消费掉：不消费就等于给了无限次猜的机会
        assertThatThrownBy(() -> captcha.verifyAndConsume(c.captchaId(), "ZZZZ"))
                .hasMessageContaining("CAPTCHA_INVALID");
        assertThatThrownBy(() -> captcha.verifyAndConsume(c.captchaId(), "ZZZZ"))
                .as("同一个 captchaId 第二次必须已经不存在")
                .hasMessageContaining("CAPTCHA_INVALID");
    }
}
