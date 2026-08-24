package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
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
 * 手机号绑定（TDD-手机号授权与自动登录）。
 *
 * <p>小程序静默登录建出来的账号**没有手机号**，而履约要联系买家：
 * 自提要发到货通知、配送要打电话。所以「什么时候把手机号补上」是这条链路的关键一环，
 * 而在此之前它<b>后端有接口、端上一次都没调过</b> —— 功能存在，但对用户不存在。
 */
@SpringBootTest
@ActiveProfiles("test")
class PhoneBindFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /*
     * 手机号用 **177 段**：135 那一段在 DevSeeder 与另外七个用例里都用过，
     * 而这套场景测试**共享同一个 H2**（前面的用例会把号占掉）。
     * 撞号的表现是本用例报 10409「手机号已属于另一个账号」——
     * 一个完全正确的业务响应，看起来却像我的代码坏了。
     */

    /** 静默登录出来的小程序用户：有 openid，**没有手机号** */
    private String wxUser(String openId) throws Exception {
        return TestLogin.consumerByWechat(mvc(), json, openId);
    }

    private int bind(String token, String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/mp/user/phone/bind")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    @Test
    @DisplayName("★★ 静默登录的用户补上手机号 → profile 读得到")
    void wxUserCanAttachPhone() throws Exception {
        String token = wxUser("wx-open-bind-1");
        assertThat(bind(token, "17700177001")).isZero();

        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.phone").exists());
    }

    @Test
    @DisplayName("★★ 重复绑同一个号 → 幂等成功，不报错")
    void bindingTheSameNumberTwiceIsIdempotent() throws Exception {
        String token = wxUser("wx-open-bind-2");
        assertThat(bind(token, "17700177002")).isZero();
        /*
         * 重复点是常态（网络慢时用户会再点一次）。第二次报错的话，
         * 用户看到「绑定失败」而他的号其实已经绑上了 —— 他会去改一个不需要改的东西。
         */
        assertThat(bind(token, "17700177002")).isZero();
    }

    @Test
    @DisplayName("★★★ 绑一个属于别人的号 → 拒绝，且**不自动合并**")
    void bindingSomeoneElsesNumberIsRejected() throws Exception {
        String owner = wxUser("wx-open-bind-3");
        assertThat(bind(owner, "17700177003")).isZero();

        /*
         * 自动合并要迁移订单、积分、卡包、优惠券、地址，横跨五个域，合错了难回滚。
         * 一期只做「检测 + 阻止」—— 但检测必须有，否则两个人会拿到同一个手机号，
         * 而那意味着**发货短信发给了另一个人**。
         */
        String other = wxUser("wx-open-bind-4");
        assertThat(bind(other, "17700177003"))
                .as("手机号已属于另一个账号时必须拒绝")
                .isNotZero();

        // 原账号不受影响
        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.data.phone").exists());
    }

    @Test
    @DisplayName("★★★ 桩通道下 capable=false，且一键接口明确报错而不是返回假号")
    void stubChannelSaysUnavailableInsteadOfFakingIt() throws Exception {
        String token = wxUser("wx-open-bind-5");

        mvc().perform(get("/mp/user/phone/capable").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.capable").value(false));

        /*
         * **不能返回一个假号码。** 它会被当成真手机号写进账号，
         * 之后发货短信、到货通知全发到一个不存在的号上 —— 而这些失败是异步的，没人会看到。
         */
        mvc().perform(post("/mp/user/phone/wx").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"anything\"}"))
                .andExpect(jsonPath("$.code").value(70027));

        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.phone").doesNotExist());
    }
}
