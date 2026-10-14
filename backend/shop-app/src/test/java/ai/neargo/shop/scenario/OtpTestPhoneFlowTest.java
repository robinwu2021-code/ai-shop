package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试号固定验证码白名单（TDD-测试号固定验证码）。
 *
 * <p>判据一律取**真实链路的可观测痕迹**，不用替身：
 * <ul>
 *   <li>「发没发短信」查 {@code sys_notify_log} —— 每条验证码短信都会在那里留一行
 *       （{@code NotifyLoggingSmsPort} 装饰器写的，成功失败都记）。
 *       用 spy 的话，替身太干净会盖住真缺陷。</li>
 *   <li>「码是哪一个」查 {@code OtpStore.peek} —— 与生产里验码读的是同一份。</li>
 * </ul>
 *
 * <p><b>每条用例都用自己的探针号，并在 finally 里删掉自己加的行。</b>
 * 表里那条 V354 种下的 13800000000 是共享种子，占着「启用中」的一个额度 ——
 * 不还原的话，单独跑绿、全量跑红，而报错会落在别的用例上（「已达上限」），
 * 与真因毫不相干。
 */
@SpringBootTest
@ActiveProfiles("test")
class OtpTestPhoneFlowTest {

    /** 与 {@code OtpTestPhoneServiceImpl.MAX_ENABLED} 同值。写死是有意的：它变了这条用例就该红 */
    private static final int MAX_ENABLED = 3;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private OtpStore otpStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ai.neargo.shop.user.service.OtpTestPhoneService service;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ── AC1 / AC2 / AC3 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 白名单号不发短信且码固定 —— 只改存码那半句的方案会给真实号段发骚扰短信")
    void whitelistedPhoneGetsFixedCodeAndNoSms() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        String phone = probePhone();
        Long id = add(token, phone, "994455", "探针");
        try {
            long smsBefore = otpLogCount(phone);
            assertThat(smsBefore).as("探针号本来就有发送记录 —— 那后面那条断言证明不了什么").isZero();

            mvc().perform(post("/biz/auth/otp/send")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + phone + "\"}"))
                    .andExpect(status().isOk());

            assertThat(otpStore.peek(phone))
                    .as("白名单号没拿到固定码 —— 苹果审核员填 994455 会登不进去")
                    .contains("994455");
            /*
             * **这一条是这个方案与「把码写进库再捞出来」的关键差别。**
             * 后者只改了存码那半句，短信照样发给一个真实号段的陌生人。
             */
            assertThat(otpLogCount(phone))
                    .as("白名单号还是发了真实短信 —— 那个号是真实号段，收到的是陌生人")
                    .isZero();
        } finally {
            remove(token, id);
        }
    }

    @Test
    @DisplayName("★★★ 非白名单号行为完全不变 —— 随机码 + 真的发")
    void nonWhitelistedPhoneUnchanged() throws Exception {
        String phone = probePhone();
        assertThat(otpLogCount(phone)).isZero();

        mvc().perform(post("/biz/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());

        assertThat(otpStore.peek(phone)).as("根本没生成码 —— 发码链路被什么挡住了").isPresent();
        assertThat(otpStore.peek(phone).orElseThrow())
                .as("非白名单号拿到了白名单里那个码 —— 命中判断写宽了")
                .isNotEqualTo("994455");
        /*
         * **反向断言不可省。** 只断言「白名单不发」的话，把 smsPort 整条注掉
         * 也能让那一条变绿 —— 而那时所有人都收不到验证码了。
         */
        assertThat(otpLogCount(phone))
                .as("非白名单号也不发短信了 —— 白名单命中判断把所有人都算进去了")
                .isEqualTo(1);
    }

    // ── AC4：最关键的一条护栏 ────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 已有 C 端账号的手机号不得录入 —— 掐掉「登进已有账号」这个用法")
    void rejectsPhoneWithExistingConsumerAccount() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        String phone = probePhone();
        // 走真实注册链路建号（发码 → 取码 → 登录），不直接插库
        TestLogin.consumer(mvc(), json, otpStore, phone);

        mvc().perform(post("/ops/test-phones")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value(10461));

        assertThat(listPhones(token)).as("被拒的号还是进了表里").doesNotContain(phone);
    }

    @Test
    @DisplayName("★★★ 已是 B 端登录号的手机号同样不得录入 —— 漏这一面等于能一键登进任意店主的店")
    void rejectsPhoneWithExistingStaffAccount() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        String phone = probePhone();
        String accountNo = "MA_PROBE_" + phone;
        /*
         * 直接插 mch_account：走真实建店链路要审核、要证照，而这条用例要验的
         * 只有一件事 —— 判「已存在账号」时**查没查 B 端那一面**。
         *
         * B 端店主与子账号登录走 mch_account.login_phone
         * （MerchantStaffServiceImpl.loginByPhone），而它与 C 端**共用同一个 OtpStore**：
         * 白名单一旦命中，那个号的固定码在 B 端也照样能用。
         * 只查 usr_identity 的话，这条护栏在 B 端完全不存在，而 C 端那边看起来是好的。
         */
        jdbc.update("INSERT INTO mch_account (mch_account_no, entity_no, is_owner, is_primary,"
                + " status, login_phone, created_at, updated_at) VALUES (?,?,?,?,?,?,NOW(),NOW())",
                accountNo, "E_PROBE_" + phone, 1, 1, "ACTIVE", phone);
        try {
            mvc().perform(post("/ops/test-phones")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + phone + "\",\"code\":\"123456\"}"))
                    .andExpect(jsonPath("$.code").value(10461));

            assertThat(listPhones(token)).as("被拒的号还是进了表里").doesNotContain(phone);
        } finally {
            // 还原共享库：留着这一行会让别处「按手机号找员工」的用例撞上一个幽灵账号
            jdbc.update("DELETE FROM mch_account WHERE mch_account_no = ?", accountNo);
        }
    }

    // ── AC5 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 增 / 启停 / 删都留审计 —— 事后要答得出「谁、何时、哪个号」")
    void everyWriteLeavesAnAuditRow() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        String phone = probePhone();
        Long id = add(token, phone, "123456", "审计探针");
        setEnabled(token, id, false);
        remove(token, id);

        for (String action : new String[]{"OTP_TEST_PHONE_ADD", "OTP_TEST_PHONE_ENABLED",
                "OTP_TEST_PHONE_REMOVE"}) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_audit_log WHERE op_action = ? AND target = ?",
                    Integer.class, action, phone);
            assertThat(n).as(action + " 没留审计 —— 这张表里每一行都是一把钥匙，动了要查得到")
                    .isNotNull().isPositive();
        }
    }

    // ── AC6 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★★ 停用后立即失效 —— 不等 60 秒缓存过期、不等重启")
    void disableTakesEffectAtOnce() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        String phone = probePhone();
        Long id = add(token, phone, "887766", "停用探针");
        try {
            /*
             * 判据取读侧的 fixedCodeFor，不再走一次 HTTP 发码：
             * 发码限流是「同一个号 60 秒一次」，同号发第二次会被闸拦下 ——
             * 那时「没拿到固定码」的原因是限流而不是停用，这条用例就变成了假绿。
             * 而 AC6 问的本来就是这一个问题：停用之后还拿不拿得到那个码。
             */
            assertThat(service.fixedCodeFor(phone))
                    .as("加完没当场生效 —— 写口没让缓存失效")
                    .contains("887766");

            setEnabled(token, id, false);

            // **当场**：不 sleep、不等 60 秒 TTL
            assertThat(service.fixedCodeFor(phone))
                    .as("停用之后那把钥匙还在 —— 出事时「当场关掉」这件事做不到")
                    .isEmpty();

            // 再启用回来同样要当场生效，否则运营会以为自己点错了
            setEnabled(token, id, true);
            assertThat(service.fixedCodeFor(phone))
                    .as("重新启用没当场生效").contains("887766");
        } finally {
            remove(token, id);
        }
        assertThat(service.fixedCodeFor(phone))
                .as("删掉之后还能拿到固定码 —— 删这一步没让缓存失效")
                .isEmpty();
    }

    // ── AC7 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 码太短、号格式不对都拒 —— 6 位下限挡的是「1234」")
    void rejectsShortCodeAndBadPhone() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        mvc().perform(post("/ops/test-phones")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + probePhone() + "\",\"code\":\"1234\"}"))
                .andExpect(jsonPath("$.code").value(10463));
        mvc().perform(post("/ops/test-phones")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"139\",\"code\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value(10464));
    }

    @Test
    @DisplayName("★★★ 启用中的条目超上限拒绝保存 —— 上限是「这张表不可能长成通用后门」的保证")
    void rejectsBeyondEnabledLimit() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        int already = enabledCount();
        assertThat(already)
                .as("库里启用中的条目已经不止 %d 条 —— 上限根本没在拦，或者别的用例没还原",
                        MAX_ENABLED)
                .isLessThanOrEqualTo(MAX_ENABLED);

        java.util.List<Long> mine = new java.util.ArrayList<>();
        try {
            // 补到刚好等于上限
            while (enabledCount() < MAX_ENABLED) {
                mine.add(add(token, probePhone(), "123456", "上限探针"));
            }
            // 再加一个必须被拒
            mvc().perform(post("/ops/test-phones")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + probePhone() + "\",\"code\":\"123456\"}"))
                    .andExpect(jsonPath("$.code").value(10462));
        } finally {
            for (Long id : mine) {
                remove(token, id);
            }
        }
        assertThat(enabledCount()).as("没还原干净 —— 下一个用例会撞上「已达上限」").isEqualTo(already);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /**
     * 一个几乎不可能与种子或别的用例撞的探针号。
     *
     * <p>{@code 199} 号段 + 纳秒后 8 位。用固定号的话，同一次全量跑里两条用例会互相
     * 拆台（一条删掉另一条刚加的行），而报错会落在毫不相干的断言上。
     */
    private static String probePhone() {
        return "199" + String.format("%08d", System.nanoTime() % 100_000_000L);
    }

    private Long add(String token, String phone, String code, String remark) throws Exception {
        String body = mvc().perform(post("/ops/test-phones")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code
                                + "\",\"remark\":\"" + remark + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data")) {
            if (phone.equals(r.get("phone").asString())) {
                return r.get("id").asLong();
            }
        }
        throw new AssertionError("加进去的号没出现在返回的列表里：" + phone);
    }

    private void setEnabled(String token, Long id, boolean on) throws Exception {
        mvc().perform(post("/ops/test-phones/" + id + "/enabled")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":" + on + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private void remove(String token, Long id) throws Exception {
        mvc().perform(post("/ops/test-phones/" + id + "/remove")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private java.util.List<String> listPhones(String token) throws Exception {
        String body = mvc().perform(get("/ops/test-phones")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode r : json.readTree(body).get("data")) {
            out.add(r.get("phone").asString());
        }
        return out;
    }

    private int enabledCount() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM usr_otp_test_phone WHERE enabled = 1 AND deleted = 0",
                Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * 这个号收到过几条真实验证码短信。{@code NotifyLoggingSmsPort} 每次发送都写一行，
     * 成功失败都写 —— 所以它是「发没发」的真实痕迹，不是一个替身的计数器。
     *
     * <p><b>按掩码后的号查</b>：{@code sys_notify_log.target} 存的是
     * {@code 199****1234}（{@code NotifyLogWriter} 写入前过一次 {@code Masks.phone}）。
     * 这件事本身值得在测试里记一笔 —— 那张表**有意不是一份号码库**。
     * 按明文查的话一条都查不到，而这条断言会静默变绿：
     * 「发了」和「没发」在明文口径下都是 0。
     */
    private long otpLogCount(String phone) {
        String masked = phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_notify_log WHERE channel = 'SMS' AND biz_type = 'OTP'"
                        + " AND target = ?", Long.class, masked);
        return n == null ? 0 : n;
    }
}
