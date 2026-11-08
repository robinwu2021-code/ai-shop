package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 版本检查（TDD-商户App问题回报与版本 V1–V5）：匿名读 {@code GET /common/app/version}。
 *
 * <p>策略行用 JDBC 直接造：这里测的是读接口本身，写接口在 {@link OpsAppVersionFlowTest}。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("版本检查：匿名可读、按语言给说明、按前缀比版本")
class AppVersionHttpTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        jdbc.update("delete from sys_app_version where app_code='MERCHANT'");
        jdbc.update("insert into sys_app_version (app_code, platform, latest_version, min_supported_version, notes,"
                + " store_url, tenant_no, created_at, updated_at) values ('MERCHANT', 'ANDROID', '0.2.0', '0.1.0', ?,"
                + " 'https://play.google.com/store/apps/details?id=ai.neargo.merchant', 'MAIN', CURRENT_TIMESTAMP,"
                + " CURRENT_TIMESTAMP)",
                "{\"zh-Hant\":[\"收款通知可以語音播報\"],\"zh-Hans\":[\"收款通知可以语音播报\"],\"en\":[\"Voice alerts\"]}");
    }

    @Test
    @DisplayName("★★★ V1 不带令牌、带过期令牌都读得到（HTTP 200、code=0），且不许缓存")
    void anonymous_and_expired_token_both_read_version() throws Exception {
        MockHttpServletResponse anon = mvc.perform(get("/common/app/version").param("platform", "android"))
                .andReturn().getResponse();
        assertThat(anon.getStatus()).isEqualTo(200);
        assertThat(json.readTree(anon.getContentAsString()).path("code").asInt()).isZero();
        assertThat(anon.getHeader("Cache-Control")).contains("no-store");

        MockHttpServletResponse expired = mvc.perform(get("/common/app/version").param("platform", "android")
                        .header("Authorization", "Bearer btk_expired_long_ago"))
                .andReturn().getResponse();
        assertThat(expired.getStatus()).isEqualTo(200);
        JsonNode data = json.readTree(expired.getContentAsString()).path("data");
        assertThat(data.path("latest").asString()).isEqualTo("0.2.0");
        assertThat(data.path("minSupported").asString()).isEqualTo("0.1.0");
        assertThat(data.path("storeUrl").asString()).startsWith("https://play.google.com/");
        assertThat(data.path("upgradeRequired").isNull()).as("不带 build 时为 null").isTrue();
    }

    @Test
    @DisplayName("★★ V2 platform 不分大小写；不认得 → 10400；缺了 → 10400")
    void platform_is_case_insensitive() throws Exception {
        assertThat(read(get("/common/app/version").param("platform", "ANDROID")).path("data").path("platform")
                .asString()).isEqualTo("ANDROID");
        assertThat(read(get("/common/app/version").param("platform", "Android")).path("code").asInt()).isZero();
    }

    @Test
    @DisplayName("★★ V2 platform 不认得或缺失 → 10400")
    void unknown_platform_is_bad_request() throws Exception {
        assertThat(read(get("/common/app/version").param("platform", "windows")).path("code").asInt()).isEqualTo(10400);
        assertThat(read(get("/common/app/version")).path("code").asInt()).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★ V3 该平台没配置：latest/minSupported 空串、notes 空数组、storeUrl 为 null")
    void unconfigured_platform_returns_empty_fields() throws Exception {
        JsonNode r = read(get("/common/app/version").param("platform", "ios").param("build", "0.1.0"));
        assertThat(r.path("code").asInt()).isZero();
        JsonNode d = r.path("data");
        assertThat(d.path("platform").asString()).isEqualTo("IOS");
        assertThat(d.path("latest").asString()).isEmpty();
        assertThat(d.path("minSupported").asString()).isEmpty();
        assertThat(d.path("notes").isArray()).isTrue();
        assertThat(d.path("notes").size()).isZero();
        assertThat(d.path("storeUrl").isNull()).isTrue();
        assertThat(d.path("upgradeRequired").isNull()).isTrue();
    }

    @Test
    @DisplayName("★★★ V4 带 build 时回 upgradeRequired；括号里的构建号不参与比较")
    void upgrade_required_when_build_below_min() throws Exception {
        assertThat(data("android", "0.0.9", null).path("upgradeRequired").asBoolean()).isTrue();
        assertThat(data("android", "0.1.0", null).path("upgradeRequired").asBoolean()).isFalse();
        assertThat(data("android", "0.1.0 (20261108.1)", null).path("upgradeRequired").asBoolean()).isFalse();
        assertThat(data("android", "0.0.9+812", null).path("upgradeRequired").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★ V5 notes 跟 Accept-Language；没填的语言回落 zh-Hant；什么都没带也是 zh-Hant")
    void notes_follow_accept_language_then_fall_back_to_zh_hant() throws Exception {
        assertThat(data("android", null, "en").path("notes").get(0).asString()).isEqualTo("Voice alerts");
        assertThat(data("android", null, "zh-Hans").path("notes").get(0).asString()).isEqualTo("收款通知可以语音播报");
        assertThat(data("android", null, "zh-CN").path("notes").get(0).asString()).isEqualTo("收款通知可以语音播报");
        assertThat(data("android", null, "ar").path("notes").get(0).asString()).as("ar 没填 → zh-Hant")
                .isEqualTo("收款通知可以語音播報");
        assertThat(data("android", null, null).path("notes").get(0).asString()).isEqualTo("收款通知可以語音播報");
        assertThat(data("android", null, "fr-FR, en;q=0.8").path("notes").get(0).asString())
                .as("取第一个能归并的").isEqualTo("Voice alerts");
    }

    @Test
    @DisplayName("★★ V5 zh-Hant-TW、zh-TW、zh-HK 都归到 zh-Hant")
    void zh_hant_tw_and_zh_tw_match_zh_hant() throws Exception {
        for (String tag : new String[] {"zh-Hant-TW", "zh-TW", "zh-HK", "zh-hant"}) {
            assertThat(data("android", null, tag + ",en;q=0.5").path("notes").get(0).asString()).as(tag)
                    .isEqualTo("收款通知可以語音播報");
        }
    }

    private JsonNode data(String platform, String build, String lang) throws Exception {
        MockHttpServletRequestBuilder b = get("/common/app/version").param("platform", platform);
        if (build != null) {
            b.param("build", build);
        }
        if (lang != null) {
            b.header("Accept-Language", lang);
        }
        JsonNode r = read(b);
        assertThat(r.path("code").asInt()).as(r.toString()).isZero();
        return r.path("data");
    }

    private JsonNode read(MockHttpServletRequestBuilder b) throws Exception {
        return json.readTree(mvc.perform(b).andReturn().getResponse().getContentAsString());
    }
}
