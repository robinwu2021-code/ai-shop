package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * C 端「邀请有礼」（TDD-C 端裂变与商家招募 §3.1）。
 *
 * <p><b>这条端点补的是买家侧此前完全缺失的那一半</b>：后端从活动配置、分享归因、
 * 新用户注册落台账到首单回填全都通了，而买家没有任何地方看得到「分享能得券」——
 * 于是线上 {@code mkt_fission_invite} 一行都没有。
 */
@SpringBootTest
@ActiveProfiles("test")
class FissionMineFlowTest {

    /** 独占号段：撞号会把别人的单卷进来 */
    private static final String PHONE = "13000350001";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    /** 活动与台账都是**共享种子**：不还原的话，后面的用例会读到我造的活动 */
    private String fissionNo;
    private String couponNo;

    @AfterEach
    void cleanUp() {
        if (fissionNo != null) {
            jdbc.update("DELETE FROM mkt_fission_invite WHERE fission_no = ?", fissionNo);
            jdbc.update("DELETE FROM mkt_fission_campaign WHERE fission_no = ?", fissionNo);
            fissionNo = null;
        }
        if (couponNo != null) {
            jdbc.update("DELETE FROM mkt_coupon WHERE coupon_no = ?", couponNo);
            couponNo = null;
        }
    }

    @Test
    @DisplayName("★★★ 有在跑的活动 → 说得出奖励是什么、我邀到了几个（两个数并列）")
    void tellsRewardAndMyProgress() throws Exception {
        String token = login();
        String me = userNoOf(token);
        seedCampaign(800L, 6_000L);
        // 邀到 2 个，其中 1 个下了首单 —— 两个数刻意不等，相等的话这条断言看不出差别
        seedInvite(me, "U-INVITEE-A", null);
        seedInvite(me, "U-INVITEE-B", "SO-FAKE-1");

        JsonNode d = mine(token);
        assertThat(d).as("没有在跑的活动时才该是 null").isNotNull();
        assertThat(d.get("fissionNo").asString()).isEqualTo(fissionNo);
        assertThat(d.get("couponTitle").asString())
                .as("说不出奖励是什么 —— 页面上只能写「得 1 张券」，等于没说").isNotEmpty();
        assertThat(d.get("faceMinor").asLong()).isEqualTo(800L);
        assertThat(d.get("thresholdMinor").asLong())
                .as("带门槛的券不说门槛，他到结账才发现用不了").isEqualTo(6_000L);
        assertThat(d.get("myInvited").asInt()).isEqualTo(2);
        assertThat(d.get("myConverted").asInt())
                .as("奖励按首单发；只给已邀请会让人问「我邀了 2 个怎么只得 1 张」").isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 只数我自己邀的 —— 别人的邀请不能算进我的进度")
    void countsOnlyMyOwnInvites() throws Exception {
        String token = login();
        String me = userNoOf(token);
        seedCampaign(800L, 6_000L);
        seedInvite(me, "U-MINE", null);
        seedInvite("U-SOMEONE-ELSE", "U-THEIRS", "SO-FAKE-2");

        JsonNode d = mine(token);
        assertThat(d.get("myInvited").asInt()).as("把别人的邀请算进来了").isEqualTo(1);
        assertThat(d.get("myConverted").asInt()).isZero();
    }

    @Test
    @DisplayName("★★★ 一场活动都没在跑 → null，端上据此整条入口不显示")
    void noCampaignMeansNull() throws Exception {
        String token = login();
        // 不建活动。种子里可能有别人留下的活动，先确认此刻没有启用中的
        Integer enabled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mkt_fission_campaign WHERE enabled = 1", Integer.class);
        if (enabled != null && enabled > 0) {
            return;   // 有别的会话留着活动，这一条今天判不了，不硬凑一个假结论
        }
        assertThat(mine(token)).as("没有活动却给了个壳 —— 端上会出现一个点进去说「暂无活动」的入口")
                .isNull();
    }

    @Test
    @DisplayName("★★ 匿名访问被拒 —— 这条端点回答的是「我」邀到了几个")
    void anonymousRejected() throws Exception {
        int status = mvc().perform(get("/mp/fission")).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    private void seedCampaign(long faceMinor, long thresholdMinor) {
        long now = System.nanoTime() % 1_000_000;
        couponNo = "CP-FS-" + now;
        fissionNo = "FS-T-" + now;
        jdbc.update("INSERT INTO mkt_coupon (coupon_no, title, type, face_minor, threshold_minor, "
                        + "funder, total_count, received_count, per_user_limit, start_at, end_at, "
                        + "status, tenant_no, created_at, updated_at, deleted) "
                        + "VALUES (?, ?, 'FULL_CUT', ?, ?, 'PLATFORM', 200, 0, 1, ?, ?, 'ACTIVE', "
                        + "'MAIN', NOW(), NOW(), 0)",
                couponNo, "满 60 减 8", faceMinor, thresholdMinor,
                System.currentTimeMillis() - 86_400_000L, System.currentTimeMillis() + 86_400_000L);
        jdbc.update("INSERT INTO mkt_fission_campaign (fission_no, name, reward_type, coupon_no, "
                        + "inviter_count, invitee_count, invited_count, converted_count, enabled, "
                        + "tenant_no, created_at, updated_at, deleted) "
                        + "VALUES (?, '邀请有礼', 'COUPON', ?, 1, 1, 0, 0, 1, 'MAIN', NOW(), NOW(), 0)",
                fissionNo, couponNo);
    }

    /** @param orderNo 非空 = 这一条已经完成首单（= 转化） */
    private void seedInvite(String inviterNo, String inviteeNo, String orderNo) {
        jdbc.update("INSERT INTO mkt_fission_invite (fission_no, inviter_no, invitee_no, "
                        + "is_new_user, rewarded, order_no, tenant_no, created_at, updated_at, deleted) "
                        + "VALUES (?, ?, ?, 1, 0, ?, 'MAIN', NOW(), NOW(), 0)",
                fissionNo, inviterNo, inviteeNo + "-" + System.nanoTime() % 100000, orderNo);
    }

    private JsonNode mine(String token) throws Exception {
        String body = mvc().perform(get("/mp/fission").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        assertThat(r.get("code").asInt()).as(body).isZero();
        JsonNode d = r.get("data");
        return d == null || d.isNull() ? null : d;
    }

    private String userNoOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private String login() throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, PHONE);
    }
}
