package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
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
 * V7 端到端：过滤器真的挂在 {@code merchantChain} 上（单测管不到「挂没挂」）。
 *
 * <p>策略经运营接口写入 —— 那条路会清掉闸门的本机缓存；直接改表的话要等 60 秒。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("旧版本写入被拒：挂在商户链上、不带头放行、回报不拦")
class ClientVersionGateFlowTest {

    /** 推送注册：普通的账号级写请求，不在七个豁免里；店主没开店也能调通 */
    private static final String PUSH = "{\"platform\":\"android\",\"clientId\":\"cid-version-gate\"}";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otpStore;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    String admin;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        admin = TestLogin.admin(mvc, json);
        jdbc.update("delete from sys_app_version where app_code='MERCHANT' and platform='ANDROID'");
        policy("{\"latest\":\"0.3.0\",\"minSupported\":\"0.2.0\",\"notes\":{\"zh-Hant\":[\"止血\"]},"
                + "\"storeUrl\":\"https://play.google.com/store/apps/details?id=ai.neargo.merchant\","
                + "\"expectedRevision\":0}");
    }

    @AfterEach
    void restore() throws Exception {
        long rev = jdbc.queryForObject("select version from sys_app_version where app_code='MERCHANT'"
                + " and platform='ANDROID'", Long.class);
        // 清掉最低版本，同时让闸门缓存失效；别的测试类不带版本头，但不留一个会拦人的策略
        policy("{\"latest\":\"0.3.0\",\"minSupported\":\"\",\"notes\":{\"zh-Hant\":[\"止血\"]},"
                + "\"storeUrl\":null,\"expectedRevision\":" + rev + "}");
    }

    @Test
    @DisplayName("★★★ 旧包写入 → 10426 CLIENT_UPGRADE_REQUIRED 带 minSupported；不带头、新包、回报都放行")
    void old_build_write_is_rejected_end_to_end() throws Exception {
        String owner = TestLogin.merchantOwner(mvc, json, otpStore, "13766200401");
        JsonNode old = write(owner, "/biz/push-token", PUSH, "android", "0.1.9");
        assertThat(old.path("code").asInt()).as(old.toString()).isEqualTo(10426);
        assertThat(old.path("msg").asString()).isEqualTo("CLIENT_UPGRADE_REQUIRED");
        assertThat(old.path("data").path("minSupported").asString()).isEqualTo("0.2.0");
        assertThat(old.path("data").path("storeUrl").asString()).startsWith("https://play.google.com/");

        assertThat(write(owner, "/biz/push-token", PUSH, null, null)
                .path("code").asInt()).as("不带头放行").isZero();
        assertThat(write(owner, "/biz/push-token", PUSH, "android", "0.2.0")
                .path("code").asInt()).isZero();
        assertThat(write(owner, "/biz/push-token", PUSH, "ios", "0.0.1")
                .path("code").asInt()).as("iOS 没配置，不拦").isZero();
        assertThat(write(owner, "/biz/feedback", "{\"category\":\"BUG\",\"description\":\"升不了级\","
                + "\"appVersion\":\"0.1.9\",\"platform\":\"android\"}", "android", "0.1.9").path("code").asInt())
                .as("强更页上的回报不拦").isZero();
    }

    private void policy(String body) throws Exception {
        String resp = mvc.perform(put("/ops/app-versions/MERCHANT/ANDROID").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(resp).path("code").asInt()).as(resp).isZero();
    }

    private JsonNode write(String token, String path, String body, String platform, String version) throws Exception {
        MockHttpServletRequestBuilder b = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (platform != null) {
            b.header("X-App-Platform", platform).header("X-App-Version", version);
        }
        return json.readTree(mvc.perform(b).andReturn().getResponse().getContentAsString());
    }
}
