package ai.neargo.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.platform.AppVersionService;
import ai.neargo.shop.platform.dto.AppVersionVOs.UpgradeRequirement;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V7 拒写闸门的判定面：哪些请求会被拦、哪些永远不拦。判「低于最低版」本身在 {@code AppVersionService}，
 * 这里一律让它回「要升级」，看的是过滤器有没有把不该拦的放过去。挂没挂在链上见 {@code ClientVersionGateFlowTest}。
 */
class ClientVersionGateFilterTest {

    private final ObjectMapper json = new ObjectMapper();
    private AppVersionService versions;
    private ClientVersionGateFilter filter;

    @BeforeEach
    void setup() {
        versions = mock(AppVersionService.class);
        when(versions.blockingUpgrade(any(), any())).thenAnswer(inv ->
                inv.getArgument(0) == null || inv.getArgument(1) == null ? Optional.empty()
                        : Optional.of(new UpgradeRequirement("0.2.0", "https://play.google.com/store/apps/details?id=x")));
        filter = new ClientVersionGateFilter(versions, json);
    }

    private MockHttpServletResponse run(String method, String path, boolean headers) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        if (headers) {
            req.addHeader("X-App-Platform", "android");
            req.addHeader("X-App-Version", "0.1.0");
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    private static boolean passed(MockHttpServletResponse resp) throws Exception {
        return resp.getContentAsString().isEmpty();
    }

    @Test
    @DisplayName("★★★ 旧包的写请求 → 10426 CLIENT_UPGRADE_REQUIRED，带 minSupported 与 storeUrl")
    void old_build_write_gets_10426() throws Exception {
        MockHttpServletResponse resp = run("POST", "/biz/goods/save", true);
        assertThat(resp.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(resp.getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(10426);
        assertThat(body.path("msg").asString()).isEqualTo("CLIENT_UPGRADE_REQUIRED");
        assertThat(body.path("data").path("minSupported").asString()).isEqualTo("0.2.0");
        assertThat(body.path("data").path("storeUrl").asString()).startsWith("https://play.google.com/");
        for (String m : new String[] {"PUT", "DELETE", "PATCH"}) {
            assertThat(passed(run(m, "/biz/goods/G1", true))).as(m).isFalse();
        }
    }

    @Test
    @DisplayName("★★★ 不带版本头的请求放行（uni-app 版 b-app 与现有 Flutter 包都不带）")
    void missing_headers_pass() throws Exception {
        assertThat(passed(run("POST", "/biz/goods/save", false))).isTrue();
    }

    @Test
    @DisplayName("★★ 读请求一律不拦")
    void reads_are_never_blocked() throws Exception {
        assertThat(passed(run("GET", "/biz/goods", true))).isTrue();
        assertThat(passed(run("HEAD", "/biz/goods", true))).isTrue();
        assertThat(passed(run("OPTIONS", "/biz/goods", true))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/biz/auth/logout", "/biz/push-token/unregister", "/biz/feedback", "/biz/auth/login",
            "/biz/auth/staff-login", "/biz/auth/otp/send", "/biz/auth/email-otp/send",
            "/biz/auth/identities", "/biz/auth/identities/enter"})
    @DisplayName("★★★ 九个不拦的写请求：退出、解绑推播、回报、六个登录类（含统一登录的验证与进身份）")
    void exempt_writes_are_never_blocked(String path) throws Exception {
        assertThat(passed(run("POST", path, true))).as(path).isTrue();
        assertThat(ClientVersionGateFilter.EXEMPT).hasSize(9);
    }

    @Test
    @DisplayName("★★ 设密码是账号写入，照拦")
    void set_password_is_blocked() throws Exception {
        assertThat(json.readTree(run("POST", "/biz/auth/password", true).getContentAsString()).path("code").asInt())
                .isEqualTo(10426);
    }
}
