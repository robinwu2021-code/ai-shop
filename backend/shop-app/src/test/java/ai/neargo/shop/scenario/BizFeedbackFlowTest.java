package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.support.TestLogin;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 商户 App 问题回报（TDD-商户App问题回报与版本 F1–F5、F9）。
 *
 * <p>断言落在库里：回报是出问题时的求助通道，「接口回 0」不等于「客服看得到」。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("商户问题回报：按账号提交、附带资料宽、正文严")
class BizFeedbackFlowTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otpStore;
    @Autowired JdbcTemplate jdbc;
    @Autowired MessageService messageService;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ F1 已登录的商户账号不选门店也能回报，拿到工单号，库里来源是商户")
    void merchant_submits_feedback_without_selecting_store() throws Exception {
        String token = owner("13766200101");
        JsonNode r = submit(token, null, Map.of("category", "BUG", "description", "列印的字太小",
                "appVersion", "0.1.0", "platform", "android"));
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        String no = r.path("data").path("feedbackNo").asString();
        assertThat(no).startsWith("TK");
        Map<String, Object> row = jdbc.queryForMap("select * from notify_ticket where ticket_no=?", no);
        assertThat(row.get("source")).isEqualTo("B_MERCHANT");
        assertThat(row.get("category")).isEqualTo("BUG");
        assertThat(row.get("client_version")).isEqualTo("0.1.0");
        assertThat(row.get("client_platform")).isEqualTo("ANDROID");
        assertThat(row.get("subject").toString()).isEqualTo("[BUG] 列印的字太小");
        assertThat(row.get("status")).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("★ F1 未登录 → 401（首期不做匿名回报）")
    void anonymous_submission_is_unauthorized() throws Exception {
        int status = mvc.perform(post("/biz/feedback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"BUG\",\"description\":\"x\",\"appVersion\":\"0.1.0\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    @Test
    @DisplayName("★★ F2 描述为空或超过 1000 字 → 10400，不落库")
    void blank_or_overlong_description_is_rejected_and_not_stored() throws Exception {
        String token = owner("13766200102");
        assertThat(submit(token, null, Map.of("category", "BUG", "description", "   ", "appVersion", "0.1.0"))
                .path("code").asInt()).isEqualTo(10400);
        assertThat(submit(token, null, Map.of("category", "BUG", "description", "长".repeat(1001),
                "appVersion", "0.1.0")).path("code").asInt()).isEqualTo(10400);
        assertThat(submit(token, null, Map.of("category", "BUG", "description", "缺版本")).path("code").asInt())
                .as("缺 appVersion").isEqualTo(10400);
        assertThat(mine(token).path("data").size()).as("被拒的一条都不该落库").isZero();
        assertThat(submit(token, null, Map.of("category", "BUG", "description", "长".repeat(1000),
                "appVersion", "0.1.0")).path("code").asInt()).as("正好 1000 字收").isZero();
    }

    @Test
    @DisplayName("★★ F2 分类不认得 → 10400")
    void unknown_category_is_rejected() throws Exception {
        String token = owner("13766200103");
        JsonNode r = submit(token, null, Map.of("category", "PRINTER", "description", "列印机", "appVersion", "0.1.0"));
        assertThat(r.path("code").asInt()).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★★ F2 App 原样发来的回报（300 条诊断、33 字错误码、macOS、不带时区的 at）照收；超长附带资料截断不拒")
    void app_shaped_report_is_accepted() throws Exception {
        String token = owner("13766200104");
        var diags = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < 301; i++) {
            diags.add(Map.of("at", "2026-11-08T17:20:00.123", "method", "GET",
                    "path", "/biz/orders/P2611051432001?phone=0912345678", "outcome", "ok", "body", "丢掉"));
        }
        var body = new java.util.HashMap<String, Object>();
        body.put("category", "PAYMENT");
        body.put("description", "退款一直转圈");
        body.put("errorCode", "business_CONTEXT_REFRESH_REQUIRED");
        body.put("page", "p".repeat(200));
        body.put("appVersion", "1.4.2 (20261108.1) 这一段非常长的构建说明会被截断到三十二个字以内");
        body.put("platform", "macOS");
        body.put("diagnostics", diags);
        JsonNode r = submit(token, null, body);
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        Map<String, Object> row = jdbc.queryForMap("select * from notify_ticket where ticket_no=?",
                r.path("data").path("feedbackNo").asString());
        assertThat(row.get("error_code")).isEqualTo("business_CONTEXT_REFRESH_REQUIRED");
        assertThat(row.get("client_platform")).isEqualTo("OTHER");
        assertThat(row.get("page").toString()).hasSize(128);
        assertThat(row.get("client_version").toString()).hasSizeLessThanOrEqualTo(32);
        JsonNode stored = json.readTree(jdbc.queryForObject("select diagnostics from notify_ticket where ticket_no=?",
                String.class, r.path("data").path("feedbackNo").asString()));
        assertThat(stored.size()).isEqualTo(300);
        assertThat(stored.get(0).path("path").asString()).isEqualTo("/biz/orders/P…2001");
        assertThat(stored.get(0).path("at").asString()).isEqualTo("2026-11-08T17:20:00.123");
        assertThat(stored.get(0).has("body")).isFalse();
    }

    @Test
    @DisplayName("★★★ F3 带了别人的门店号：丢掉门店号、照常落库、记 store_dropped=1")
    void store_no_outside_my_stores_is_dropped_and_feedback_still_stored() throws Exception {
        String other = owner("13766200105");
        String othersStore = quickStart(other);
        String token = owner("13766200106");
        JsonNode r = submit(token, null, Map.of("category", "BUG", "description", "门店进不去",
                "appVersion", "0.1.0", "platform", "ios", "storeNo", othersStore));
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        Map<String, Object> row = jdbc.queryForMap("select store_no, store_dropped from notify_ticket where ticket_no=?",
                r.path("data").path("feedbackNo").asString());
        assertThat(row.get("store_no")).as("别人的门店号不能挂上来").isNull();
        assertThat(((Number) row.get("store_dropped")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ F3 带了自己的门店号：存下")
    void store_no_of_my_store_is_kept() throws Exception {
        String token = owner("13766200107");
        String store = quickStart(token);
        JsonNode r = submit(token, store, Map.of("category", "IDEA", "description", "想要语音播报",
                "appVersion", "0.1.0", "platform", "android", "storeNo", store));
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        Map<String, Object> row = jdbc.queryForMap("select store_no, store_dropped from notify_ticket where ticket_no=?",
                r.path("data").path("feedbackNo").asString());
        assertThat(row.get("store_no")).isEqualTo(store);
        assertThat(((Number) row.get("store_dropped")).intValue()).isZero();
        assertThat(mine(token).path("data").get(0).path("storeNo").asString()).isEqualTo(store);
    }

    @Test
    @DisplayName("★★ F5 我的回报：只列本账号的商户回报，新的在前，最多 50 条，时间是 ISO 字符串")
    void mine_lists_only_my_merchant_feedback_newest_first_capped_at_50() throws Exception {
        String token = owner("13766200108");
        JsonNode first = submit(token, null, Map.of("category", "OTHER", "description", "第一条", "appVersion", "0.1.0"));
        String userNo = jdbc.queryForObject("select user_no from notify_ticket where ticket_no=?", String.class,
                first.path("data").path("feedbackNo").asString());
        for (int i = 0; i < 55; i++) {
            jdbc.update("insert into notify_ticket (ticket_no, user_no, subject, content, status, source, category,"
                    + " tenant_no, created_at, updated_at) values (?,?,?,?, 'OPEN', 'B_MERCHANT', 'OTHER', 'MAIN',"
                    + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", "TKFLOOD" + userNo.hashCode() + "_" + i, userNo,
                    "[OTHER] 灌" + i, "灌" + i);
        }
        submit(token, null, Map.of("category", "BUG", "description", "最新一条", "appVersion", "0.1.0"));
        JsonNode list = mine(token).path("data");
        assertThat(list.size()).isEqualTo(50);
        assertThat(list.get(0).path("description").asString()).isEqualTo("最新一条");
        assertThat(list.get(0).path("status").asString()).isEqualTo("OPEN");
        assertThat(list.get(0).path("createdAt").asString()).endsWith("Z");
        assertThat(list.get(0).path("category").asString()).isEqualTo("BUG");
    }

    @Test
    @DisplayName("★★ F9 同一账号 10 分钟内第 6 条 → 10429；被拒的不计数也不落库")
    void sixth_submission_within_window_is_rate_limited() throws Exception {
        String token = owner("13766200109");
        for (int i = 0; i < 5; i++) {
            assertThat(submit(token, null, Map.of("category", "BUG", "description", "第" + i, "appVersion", "0.1.0"))
                    .path("code").asInt()).isZero();
        }
        JsonNode sixth = submit(token, null, Map.of("category", "BUG", "description", "第6", "appVersion", "0.1.0"));
        assertThat(sixth.path("code").asInt()).isEqualTo(10429);
        assertThat(mine(token).path("data").size()).isEqualTo(5);
    }

    @Test
    @DisplayName("★★ 已拍板 P7：90 天前的回报清空诊断资料，工单保留；新的不动")
    void diagnostics_older_than_90_days_are_purged_ticket_kept() throws Exception {
        String token = owner("13766200110");
        Map<String, Object> body = Map.of("category", "BUG", "description", "旧的", "appVersion", "0.1.0",
                "diagnostics", java.util.List.of(Map.of("at", "2026-01-01T00:00:00Z", "method", "GET",
                        "path", "/biz/a", "outcome", "ok")));
        String old = submit(token, null, body).path("data").path("feedbackNo").asString();
        String fresh = submit(token, null, body).path("data").path("feedbackNo").asString();
        jdbc.update("update notify_ticket set created_at=? where ticket_no=?",
                java.time.LocalDateTime.now().minusDays(91), old);

        messageService.purgeTicketDiagnostics(java.time.LocalDateTime.now().minusDays(90));

        assertThat(jdbc.queryForObject("select diagnostics from notify_ticket where ticket_no=?", String.class, old))
                .isNull();
        assertThat(jdbc.queryForObject("select content from notify_ticket where ticket_no=?", String.class, old))
                .isEqualTo("旧的");
        assertThat(jdbc.queryForObject("select diagnostics from notify_ticket where ticket_no=?", String.class, fresh))
                .isNotNull();
    }

    // ---------------------------------------------------------------- helpers

    private String owner(String phone) throws Exception {
        return TestLogin.merchantOwner(mvc, json, otpStore, phone);
    }

    private String quickStart(String token) throws Exception {
        String body = mvc.perform(post("/biz/merchant/quick-start").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeName\":\"Feedback shop\"}"))
                .andReturn().getResponse().getContentAsString();
        String store = json.readTree(body).path("data").path("currentStoreNo").asString();
        assertThat(store).as(body).isNotBlank();
        return store;
    }

    private JsonNode submit(String token, String storeHeader, Map<String, ?> body) throws Exception {
        MockHttpServletRequestBuilder b = post("/biz/feedback").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (storeHeader != null) {
            b.header("X-Store-No", storeHeader);
        }
        return json.readTree(mvc.perform(b).andReturn().getResponse().getContentAsString());
    }

    private JsonNode mine(String token) throws Exception {
        return json.readTree(mvc.perform(get("/biz/feedback/mine").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }
}
