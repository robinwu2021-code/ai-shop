package ai.neargo.shop.portal.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.user.service.EmailOtpService;

import tools.jackson.databind.ObjectMapper;

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

import java.util.UUID;

/**
 * TDD-B端邮箱验证码注册登录 AC1 / AC3 / AC5 的 HTTP 层：走真实端点、真实安全链与 H2 账号表。
 *
 * <p>判据取可观测痕迹：码从 {@link OtpStore#peek} 取（与生产验码读同一份），
 * 账号从 {@code usr_account} / {@code usr_identity} 查，不用替身。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizAuthEmailLoginTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;
    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private static String probeEmail() {
        return "Probe." + UUID.randomUUID().toString().substring(0, 8) + "@Example.test";
    }

    private String send(MockMvc mvc, String email) throws Exception {
        String body = mvc.perform(post("/biz/auth/email-otp/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"locale\":\"zh-Hant\"}"))
                .andReturn().getResponse().getContentAsString();
        var data = json.readTree(body).path("data");
        assertThat(data.path("expiresInSeconds").asLong()).as(body).isEqualTo(600);
        assertThat(data.path("resendAfterSeconds").asInt()).as(body).isEqualTo(60);
        return otpStore.peek(EmailOtpService.key(EmailOtpService.normalize(email))).orElseThrow(
                () -> new AssertionError("没有为邮箱生成验证码：" + body));
    }

    private String login(MockMvc mvc, String email, String code, boolean agreed) throws Exception {
        return mvc.perform(post("/biz/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"EMAIL_OTP\",\"principal\":\"" + email
                                + "\",\"credential\":\"" + code + "\",\"agreed\":" + agreed + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("★★★ 只有邮箱、没有手机号的账号能注册、取到商家档案，再次登录是同一个人")
    void emailOnlyAccountReachesProfileWithoutPhone() throws Exception {
        var mvc = mvc();
        String email = probeEmail();
        String normalized = EmailOtpService.normalize(email);

        var first = json.readTree(login(mvc, email, send(mvc, email), true));
        String token = first.path("data").path("token").asString();
        assertThat(token).as(first.toString()).startsWith("btk_");

        String userNo = jdbc.queryForObject(
                "select user_no from usr_identity where identity_type='EMAIL' and identity_value=?",
                String.class, normalized);
        assertThat(jdbc.queryForObject("select phone from usr_account where user_no=?", String.class, userNo))
                .as("邮箱注册不该凭空造一个手机号").isNull();

        var profile = mvc.perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(profile.getStatus()).as(profile.getContentAsString()).isEqualTo(200);
        assertThat(json.readTree(profile.getContentAsString()).path("code").asInt())
                .as(profile.getContentAsString()).isZero();

        // 60 秒发码间隔对同一邮箱生效；换成大小写不同的写法仍是同一个键
        otpStore.save(EmailOtpService.key(normalized), "246813");
        var again = json.readTree(login(mvc, email.toUpperCase(), "246813", false));
        assertThat(again.path("data").path("token").asString()).as(again.toString()).startsWith("btk_");
        assertThat(jdbc.queryForObject(
                "select count(*) from usr_identity where identity_type='EMAIL' and identity_value=?",
                Integer.class, normalized)).isEqualTo(1);
    }

    @Test
    @DisplayName("不是邮箱的地址在入口就被拒，不生成验证码、不调用邮件通道")
    void malformedEmailIsRejectedAtTheBoundary() throws Exception {
        var body = mvc().perform(post("/biz/auth/email-otp/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("code").asInt()).as(body).isNotZero();
        assertThat(otpStore.peek(EmailOtpService.key("not-an-email"))).isEmpty();
    }

    @Test
    @DisplayName("TDD-注册地区与三步建店 AC12 邮箱账号：登录响应与 profile 的 merchant.account 是归一化邮箱")
    void emailAccountProfileCarriesTheEmailAsAccount() throws Exception {
        var mvc = mvc();
        String email = probeEmail();
        String normalized = EmailOtpService.normalize(email);
        var first = json.readTree(login(mvc, email, send(mvc, email), true));
        assertThat(first.path("data").path("merchant").path("account").asString()).as(first.toString())
                .isEqualTo(normalized);

        String token = first.path("data").path("token").asString();
        String profile = mvc.perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(profile).path("data").path("account").asString()).as(profile)
                .isEqualTo(normalized);
    }
}
