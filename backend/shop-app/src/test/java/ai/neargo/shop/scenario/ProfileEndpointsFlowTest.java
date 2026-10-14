package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * C-AC-08 个人资料的三条端点，走真实链路（TDD-C端个人资料与密码 §5 · AC4/AC5/AC7）。
 *
 * <p>与 {@code ProfileAndPasswordTest} 的分工：那边用 Mockito 验**判据**
 * （昵称长度、「没手机号不许设密码」），这边验**端点真的接上了** ——
 * 字节存下来并落到账号上、密码设完状态真的变了。
 *
 * <p>用手机号登录（{@link TestLogin#consumer}）而不是微信登录：
 * 这条路会在 {@code usr_identity} 留下 PHONE 凭证，而设密码要求它存在。
 * 拿微信登录的会话来测，第一条断言就会撞上 10504 —— 那不是缺陷，是前置条件没满足。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProfileEndpointsFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    /**
     * <b>{@code springSecurity()} 不能省。</b>
     *
     * <p>令牌过滤器是注册在安全链上的，而 {@code webAppContextSetup} 默认不装它 ——
     * 省掉这一行的话每个带 {@code Authorization} 头的请求都回 10401「请先登录」，
     * 而症状出现在**第一次发验证码**那一步（{@code /mp/user/otp/send} 要求已有会话），
     * 报出来的话是「没有为 xxx 生成验证码 —— 发码那一步是不是被限流拦了？」。
     * 与限流毫无关系。
     */
    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private JsonNode call(String token, org.springframework.test.web.servlet.RequestBuilder rb)
            throws Exception {
        return json.readTree(mvc().perform(rb).andReturn().getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ AC4

    @Test
    @DisplayName("★★★ AC4 传头像：字节存下来，并当场落到账号上")
    void avatarUploadLandsOnTheAccount() throws Exception {
        String tk = login("13900000101");

        JsonNode before = call(tk, get("/mp/user/profile").header("Authorization", "Bearer " + tk));
        assertThat(before.path("data").path("avatar").asString("")).isEmpty();

        JsonNode r = call(tk, multipart("/mp/user/avatar")
                .file(new MockMultipartFile("file", "a.png", "image/png", png()))
                .header("Authorization", "Bearer " + tk));
        assertThat(r.path("code").asInt()).isEqualTo(0);

        /*
         * **判的是返回体里的 avatar，而且要再读一次 profile。**
         *
         * 只判返回体的话，一个「存了字节但没写库」的实现也能绿 ——
         * 它完全可以把刚算出来的 url 原样回给端上，而账号上还是空的。
         * 用户看到头像变了，下次进来又没了。
         */
        String url = r.path("data").path("avatar").asString("");
        assertThat(url).isNotEmpty();

        JsonNode after = call(tk, get("/mp/user/profile").header("Authorization", "Bearer " + tk));
        assertThat(after.path("data").path("avatar").asString("")).isEqualTo(url);
    }

    @Test
    @DisplayName("★★★ AC4 不是图的字节被拒 —— 后缀是客户端说了算的")
    void nonImageBytesRejected() throws Exception {
        String tk = login("13900000102");
        JsonNode r = call(tk, multipart("/mp/user/avatar")
                .file(new MockMultipartFile("file", "x.png", "image/png",
                        "这是一段纯文本，只是名字叫 png".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + tk));
        // 少了这一道，任意字节都能以 image/png 落进**公开**目录并公开取回
        assertThat(r.path("code").asInt()).isNotEqualTo(0);

        JsonNode after = call(tk, get("/mp/user/profile").header("Authorization", "Bearer " + tk));
        assertThat(after.path("data").path("avatar").asString("")).isEmpty();
    }

    @Test
    @DisplayName("★★ AC4 一个字节都没传也被拒，且回的是 401 之后的参数错，不是 500")
    void missingFileRejected() throws Exception {
        String tk = login("13900000103");
        JsonNode r = call(tk, multipart("/mp/user/avatar").header("Authorization", "Bearer " + tk));
        assertThat(r.path("code").asInt()).isEqualTo(10400);
    }

    // ------------------------------------------------------------------ AC5 / AC7

    @Test
    @DisplayName("★★★ AC5 设完密码，真的能用它登录（不是只往库里写了一行）")
    void passwordCanActuallySignYouIn() throws Exception {
        String phone = "13900000104";
        String tk = login(phone);

        JsonNode set = call(tk, post("/mp/user/password")
                .header("Authorization", "Bearer " + tk)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"pw-c-ac-08\"}"));
        assertThat(set.path("code").asInt()).isEqualTo(0);

        /*
         * **这一步才是 AC5 的全部意义。**
         *
         * 只断言「usr_identity 多了一行 PASSWORD」的话，哈希算错、
         * 存进了别人的 user_no、或者密码登录那条路根本没接上 —— 三种都照样绿，
         * 而用户的症状是「我明明设了密码却登不进去」。
         */
        JsonNode in = call(null, post("/mp/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of(
                        "grantType", "PASSWORD",
                        "principal", phone,
                        "credential", "pw-c-ac-08",
                        "agreed", true))));
        assertThat(in.path("code").asInt()).isEqualTo(0);
        assertThat(in.path("data").path("token").asString("")).isNotEmpty();
        // 登进来的必须是同一个人，不是新开的号
        assertThat(in.path("data").path("user").path("userNo").asString(""))
                .isEqualTo(call(tk, get("/mp/user/profile").header("Authorization", "Bearer " + tk))
                        .path("data").path("userNo").asString(""));
    }

    @Test
    @DisplayName("★★★ AC5 改密码之后旧密码立刻失效 —— 否则「改」只是「又加了一个」")
    void oldPasswordStopsWorkingAfterChange() throws Exception {
        String phone = "13900000105";
        String tk = login(phone);
        setPassword(tk, "first-password");
        setPassword(tk, "second-password");

        assertThat(loginByPassword(phone, "second-password").path("code").asInt()).isEqualTo(0);
        assertThat(loginByPassword(phone, "first-password").path("code").asInt()).isNotEqualTo(0);
    }

    @Test
    @DisplayName("★★★ AC7 password 状态随设置变化，canSet 反映有没有手机号")
    void passwordStateTracksReality() throws Exception {
        String tk = login("13900000106");

        JsonNode before = call(tk, get("/mp/user/password").header("Authorization", "Bearer " + tk));
        assertThat(before.path("data").path("hasPassword").asBoolean()).isFalse();
        // 手机号登录进来的人天然有 PHONE 凭证 —— canSet 必须是 true，
        // 恒 false 的实现会让所有人都看到「先绑手机号」而他明明绑着
        assertThat(before.path("data").path("canSet").asBoolean()).isTrue();

        setPassword(tk, "pw-state-check");

        JsonNode after = call(tk, get("/mp/user/password").header("Authorization", "Bearer " + tk));
        assertThat(after.path("data").path("hasPassword").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★★ AC6 微信登录（没手机号）的人设密码被拒 —— 那条密码永远登不进来")
    void wechatOnlyUserCannotSetPassword() throws Exception {
        /*
         * 这是 ProfileAndPasswordTest 里那条单测的真实链路版。
         * 微信登录不留 PHONE 凭证，而密码登录第一步就是按 PHONE 找人 ——
         * 线上已经有一条这样的死数据（见 TDD §1）。
         */
        String tk = TestLogin.consumerByWechat(mvc(), json, "openid-c-ac-08-nophone");

        JsonNode state = call(tk, get("/mp/user/password").header("Authorization", "Bearer " + tk));
        assertThat(state.path("data").path("canSet").asBoolean()).isFalse();

        JsonNode set = call(tk, post("/mp/user/password")
                .header("Authorization", "Bearer " + tk)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"long-enough-password\"}"));
        assertThat(set.path("code").asInt()).isEqualTo(10504);
    }

    // ------------------------------------------------------------------ 脚手架

    private void setPassword(String token, String pw) throws Exception {
        JsonNode r = call(token, post("/mp/user/password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("password", pw))));
        assertThat(r.path("code").asInt()).as("设密码应当成功").isEqualTo(0);
    }

    private JsonNode loginByPassword(String phone, String pw) throws Exception {
        return call(null, post("/mp/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of(
                        "grantType", "PASSWORD", "principal", phone,
                        "credential", pw, "agreed", true))));
    }

    /** 最小的合法 PNG 头 —— 要过 ImageProbe 的 magic number。 */
    private static byte[] png() {
        byte[] b = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, b, 0, magic.length);
        return b;
    }
}
