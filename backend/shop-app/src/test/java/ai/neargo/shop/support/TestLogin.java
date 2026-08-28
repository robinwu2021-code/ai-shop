package ai.neargo.shop.support;

import ai.neargo.shop.common.OtpStore;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 三端登录的**唯一一份实现**。
 *
 * <p><b>为什么要有它</b>：登录是几乎每条测试旅程的第一步，于是
 * 「发码 → 取码 → 登录」这三行在 <b>37 个测试类里各抄了一遍</b>
 * （`/mp/user/login` 37 个文件、`/ops/auth/login` 37 个文件，
 * `M9aOpsFlowTest` 甚至在自己类里写了 4 份）。
 *
 * <p>代价不是「多写几行」，是**改登录链路时要改 37 处**：
 * <ul>
 *   <li>这一轮加发码限流时，如果阈值需要测试侧配合（比如每个类换号），37 个类各改一遍</li>
 *   <li>试过把取码来源从 {@code OtpStore.peek} 换成短信桩 —— 37 个文件，
 *       成本大到让人先想「这件事值不值得做」。有了这里，它是一个文件一行</li>
 * </ul>
 *
 * <p><b>刻意做成静态方法而不是基类</b>：测试类各有各的 {@code @SpringBootTest}
 * 配置（不同 profile、不同内存库），强制继承一个基类会把那些差异挤没；
 * 而登录只需要 {@code MockMvc} 与两个 bean，传参就够。
 */
public final class TestLogin {

    private TestLogin() {
    }

    /**
     * C 端消费者登录：发码 → 取码 → 换 token。
     *
     * @return 形如 {@code ctk_...} 的令牌
     */
    public static String consumer(MockMvc mvc, ObjectMapper json, OtpStore otpStore, String phone)
            throws Exception {
        mvc.perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        /*
         * 取码走 OtpStore.peek —— 它只在测试里有调用方。
         * **不给生产开「万能验证码」后门**：那种后门一旦漏到线上就是任意账号登录，
         * 而这里读的是真实生成的那一条，走的是真实的发码-校验链路。
         */
        String code = otpStore.peek(phone).orElseThrow(
                () -> new AssertionError("没有为 " + phone + " 生成验证码 —— 发码那一步是不是被限流拦了？"));
        String body = mvc.perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return token(json, body, "C 端登录");
    }

    /** 微信一键登录（没有验证码这一步）。 */
    public static String consumerByWechat(MockMvc mvc, ObjectMapper json, String openid)
            throws Exception {
        String body = mvc.perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"WECHAT_MP\",\"principal\":\"" + openid
                                + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return token(json, body, "微信登录");
    }

    /** 运营端登录（用户名 + 密码）。 */
    public static String operator(MockMvc mvc, ObjectMapper json, String username, String password)
            throws Exception {
        String body = mvc.perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return token(json, body, "运营端登录");
    }

    /** 运营端超管，测试里最常用的那个。 */
    public static String admin(MockMvc mvc, ObjectMapper json) throws Exception {
        return operator(mvc, json, "admin", "admin123");
    }

    /**
     * B 端店主登录：发码 → 取码 → 换 <b>btk_</b>。
     *
     * <p>A7 之前测试是拿 {@link #consumer} 的 {@code ctk_} 直接打 {@code /biz/**} 的 ——
     * 那时两端共用一个令牌前缀，能过。<b>A7 之后 {@code /biz/**} 只认 btk_</b>，
     * 于是那 40 多个 helper 全部 401，而这恰恰是这次改动要消灭的那件事：
     * C 端令牌不该能操作商家后台。
     *
     * <p>与 {@link #merchantStaff} 的区别：店员走 {@code /biz/auth/staff-login}
     * （查 mch_staff），店主走 {@code /biz/auth/login}（就是 C 端那套账号体系，
     * 但发 B 端令牌）—— 还没开店的人也走这一支。
     *
     * @return 形如 {@code btk_...} 的令牌
     */
    public static String merchantOwner(MockMvc mvc, ObjectMapper json, OtpStore otpStore, String phone)
            throws Exception {
        mvc.perform(post("/biz/auth/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow(
                () -> new AssertionError("没有为 " + phone + " 生成验证码 —— 发码那一步是不是被限流拦了？"));
        String body = mvc.perform(post("/biz/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return token(json, body, "B 端店主登录");
    }

    /** B 端店员登录（手机号 + 验证码，不需要 C 端账号）。 */
    public static String merchantStaff(MockMvc mvc, ObjectMapper json, OtpStore otpStore, String phone)
            throws Exception {
        mvc.perform(post("/biz/auth/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow(
                () -> new AssertionError("没有为 " + phone + " 生成验证码"));
        String body = mvc.perform(post("/biz/auth/staff-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return token(json, body, "B 端店员登录");
    }

    /**
     * 从响应里取 token，**失败时把整个响应体打出来**。
     *
     * <p>此前各处是 {@code .get("data").get("token").asString()} ——
     * 登录失败时报的是 {@code NullPointerException: get("token") is null}，
     * 看不出到底是密码错、被限流、还是账号被停用。而登录是第一步，
     * 它一挂后面全挂，最需要一眼看清原因的恰恰是这里。
     */
    private static String token(ObjectMapper json, String body, String what) {
        JsonNode root = json.readTree(body);
        JsonNode data = root.get("data");
        if (data == null || data.get("token") == null) {
            throw new AssertionError(what + "失败，响应：" + body);
        }
        return data.get("token").asString();
    }
}
