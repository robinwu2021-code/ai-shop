package ai.neargo.shop.portal.internal;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 主系统给元器件独立服务开的三个内部端点 —— <b>用真令牌、真会话库、真站内信</b>。
 *
 * <p>elec-svc 那一侧用替身顶着这三个端点（FakeMainSystem）；替身照这里的口径回话，
 * 这里钉住的就是那份口径：查不到的令牌是 expired、响应不被全局信封包、没密钥一律拒。
 * 两边共用 elec-api 里的路径与 record，契约的形状由编译器看着。
 */
@SpringBootTest
@ActiveProfiles("test")
class InternalElecEndpointTest {

    private static final String KEY = "test-internal-token";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ C 端令牌 → 有效的 CONSUMER，带用户号；响应就是 record 本身，不被全局信封包")
    void consumerToken() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "12600930001");
        ElecInternal.Session s = session(token, KEY);
        assertThat(s.valid()).isTrue();
        assertThat(s.realm()).isEqualTo("CONSUMER");
        assertThat(s.userNo()).isNotBlank();
        assertThat(s.perms()).isEmpty();
    }

    @Test
    @DisplayName("★★★ 查不到会话的令牌 → expired=true（与主系统自己的过滤器同一个口径）")
    void unknownTokenIsExpired() throws Exception {
        ElecInternal.Session s = session("ctk_no-such-session", KEY);
        assertThat(s.valid()).isFalse();
        assertThat(s.expired()).isTrue();
    }

    @Test
    @DisplayName("★★★ 运营令牌 → OPERATOR，权限只回 OPS_PERMS 里的码（超管有 *，六个都有、一个别的都没有）")
    void operatorTokenCarriesOnlyElecPerms() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        ElecInternal.Session s = session(token, KEY);
        assertThat(s.realm()).isEqualTo("OPERATOR");
        // 精确相等两头都量：少了 = 新码没带过去（元器件那边一律 403）；多了 = 把主系统的权限表漏给了另一个服务
        assertThat(s.perms()).containsExactlyInAnyOrderElementsOf(ElecInternal.OPS_PERMS);
        assertThat(ElecInternal.OPS_PERMS).as("量具本身：六个码").hasSize(6);
    }

    @Test
    @DisplayName("★★★ 没带密钥、密钥不对：401，一个字都不回")
    void requiresKey() throws Exception {
        String body = "{\"token\":\"x\"}";
        assertThat(status(post(ElecInternal.SESSION).contentType(MediaType.APPLICATION_JSON).content(body), null))
                .isEqualTo(401);
        assertThat(status(post(ElecInternal.SESSION).contentType(MediaType.APPLICATION_JSON).content(body), "wrong"))
                .isEqualTo(401);
    }

    private ElecInternal.Session session(String token, String key) throws Exception {
        String req = json.writeValueAsString(new ElecInternal.SessionReq(token));
        return json.readValue(body(post(ElecInternal.SESSION).contentType(MediaType.APPLICATION_JSON).content(req), key),
                ElecInternal.Session.class);
    }

    private String body(MockHttpServletRequestBuilder req, String key) throws Exception {
        if (key != null) {
            req.header(ElecInternal.TOKEN_HEADER, key);
        }
        var res = mvc().perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return res.getContentAsString();
    }

    private int status(MockHttpServletRequestBuilder req, String key) throws Exception {
        if (key != null) {
            req.header(ElecInternal.TOKEN_HEADER, key);
        }
        return mvc().perform(req).andReturn().getResponse().getStatus();
    }
}
