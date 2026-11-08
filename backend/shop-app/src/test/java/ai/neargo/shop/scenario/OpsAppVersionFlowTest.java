package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * 运营端 · App 版本策略（TDD-商户App问题回报与版本 V6）。
 *
 * <p>只动 {@code MERCHANT/IOS} 那一行：{@link AppVersionHttpTest} 用的是 ANDROID，
 * 两边在同一个上下文里，不互相踩。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("运营端 App 版本：能看、能改、改了留痕、改最低版另要一个码")
class OpsAppVersionFlowTest {

    private static final String PATH = "/ops/app-versions/MERCHANT/IOS";
    private static final String APPLE = "https://apps.apple.com/app/id6740000000";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        jdbc.update("delete from sys_app_version where app_code='MERCHANT' and platform='IOS'");
    }

    @Test
    @DisplayName("★★ 列表：两个平台都有一行，没配过的 revision=0、字段为空")
    void list_shows_both_platforms() throws Exception {
        JsonNode r = read(TestLogin.admin(mvc, json));
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        JsonNode ios = null;
        for (JsonNode row : r.path("data")) {
            if ("IOS".equals(row.path("platform").asString()) && "MERCHANT".equals(row.path("appCode").asString())) {
                ios = row;
            }
        }
        assertThat(ios).as(r.toString()).isNotNull();
        assertThat(ios.path("revision").asLong()).isZero();
        assertThat(ios.path("latest").asString()).isEmpty();
    }

    @Test
    @DisplayName("★★★ minSupported 大于 latest → 10400，不落库")
    void min_above_latest_is_rejected() throws Exception {
        JsonNode r = save(TestLogin.admin(mvc, json), "0.2.0", "0.3.0", APPLE, 0);
        assertThat(r.path("code").asInt()).isEqualTo(10400);
        assertThat(jdbc.queryForObject("select count(*) from sys_app_version where app_code='MERCHANT' and platform='IOS'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("★★★ 商店地址只能是两家商店的 https 地址")
    void store_url_must_be_app_store_or_play() throws Exception {
        String admin = TestLogin.admin(mvc, json);
        for (String bad : new String[] {"https://evil.example.com/app", "http://apps.apple.com/app/id1",
                "https://apps.apple.com.evil.example/x"}) {
            assertThat(save(admin, "0.2.0", "0.1.0", bad, 0).path("code").asInt()).as(bad).isEqualTo(10400);
        }
        assertThat(save(admin, "0.2.0", "0.1.0", APPLE, 0).path("code").asInt()).isZero();
    }

    @Test
    @DisplayName("★★ zh-Hant 更新说明必填（其它语言可空、回落 zh-Hant）")
    void zh_hant_notes_are_required() throws Exception {
        String body = "{\"latest\":\"0.2.0\",\"minSupported\":\"0.1.0\",\"notes\":{\"en\":[\"x\"]},"
                + "\"storeUrl\":\"" + APPLE + "\",\"expectedRevision\":0}";
        assertThat(send(TestLogin.admin(mvc, json), body).path("code").asInt()).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★★ 两个人同时改：拿旧 revision 的那个 → 10409")
    void stale_revision_conflicts() throws Exception {
        String admin = TestLogin.admin(mvc, json);
        JsonNode first = save(admin, "0.2.0", "0.1.0", APPLE, 0);
        assertThat(first.path("code").asInt()).as(first.toString()).isZero();
        long rev = first.path("data").path("revision").asLong();
        JsonNode second = save(admin, "0.3.0", "0.1.0", APPLE, rev);
        assertThat(second.path("code").asInt()).as(second.toString()).isZero();
        assertThat(second.path("data").path("revision").asLong()).isGreaterThan(rev);
        assertThat(save(admin, "0.4.0", "0.1.0", APPLE, rev).path("code").asInt()).isEqualTo(10409);
        assertThat(save(admin, "0.4.0", "0.1.0", APPLE, 0).path("code").asInt())
                .as("已经有行了还按「新建」提交").isEqualTo(10409);
    }

    @Test
    @DisplayName("★★ 每次改动写审计")
    void update_writes_audit_log() throws Exception {
        Integer before = jdbc.queryForObject("select count(*) from sys_audit_log where op_action='APP_VERSION_UPDATE'"
                + " and target='MERCHANT:IOS'", Integer.class);
        assertThat(save(TestLogin.admin(mvc, json), "0.2.0", "0.1.0", APPLE, 0).path("code").asInt()).isZero();
        Integer after = jdbc.queryForObject("select count(*) from sys_audit_log where op_action='APP_VERSION_UPDATE'"
                + " and target='MERCHANT:IOS'", Integer.class);
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("★★★ 已拍板 P5：技术运维能看、不能改（改最低版要 system:app-version:update）")
    void tech_ops_reads_but_cannot_update() throws Exception {
        String techops = TestLogin.operator(mvc, json, "techops", "techops123");
        assertThat(read(techops).path("code").asInt()).isZero();
        assertThat(save(techops, "0.2.0", "0.1.0", APPLE, 0).path("code").asInt()).isEqualTo(10403);
    }

    private JsonNode read(String token) throws Exception {
        return json.readTree(mvc.perform(get("/ops/app-versions").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode save(String token, String latest, String min, String url, long revision) throws Exception {
        return send(token, "{\"latest\":\"" + latest + "\",\"minSupported\":\"" + min + "\","
                + "\"notes\":{\"zh-Hant\":[\"修正退款偶爾轉圈\"]},\"storeUrl\":\"" + url + "\","
                + "\"expectedRevision\":" + revision + "}");
    }

    private JsonNode send(String token, String body) throws Exception {
        return json.readTree(mvc.perform(put(PATH).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
    }
}
