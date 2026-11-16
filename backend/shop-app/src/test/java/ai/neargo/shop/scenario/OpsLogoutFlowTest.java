package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运营端退出：服务端吊销令牌（TDD-ops-统一登录与系统切换 §3.3）。
 *
 * <p>此前退出只清浏览器，令牌在过期前仍可调用 /ops/** —— 而平台运营与支付运营共用这一个令牌，
 * 一边「退出」后另一边照样能用。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("运营端退出吊销令牌")
class OpsLogoutFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("退出后同一令牌访问 /ops/auth/me 被拒")
    void logoutRevokesToken() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc().perform(post("/ops/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("幂等：重复退出、过期令牌、不带令牌都返回成功")
    void logoutIsIdempotent() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        mvc().perform(post("/ops/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc().perform(post("/ops/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc().perform(post("/ops/auth/logout").header("Authorization", "Bearer ops-not-a-real-token"))
                .andExpect(status().isOk());
        mvc().perform(post("/ops/auth/logout"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("非 Bearer 头不吊销票据，正确前缀沿统一解析去除票据两侧空白")
    void logoutPreservesBearerParsingAtTheBoundHeaderBoundary() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        for (String header : new String[]{"bearer " + token, "BEARER " + token,
                "Basic " + token, "Bearer\t" + token, "Bearer "}) {
            mvc().perform(post("/ops/auth/logout").header("Authorization", header))
                    .andExpect(status().isOk());
            mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        mvc().perform(post("/ops/auth/logout").header("Authorization", "Bearer   " + token + "  "))
                .andExpect(status().isOk());
        mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("只吊销自己的令牌：另一个会话不受影响")
    void logoutOnlyRevokesPresentedToken() throws Exception {
        String first = TestLogin.admin(mvc(), json);
        String second = TestLogin.admin(mvc(), json);
        mvc().perform(post("/ops/auth/logout").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk());
        mvc().perform(get("/ops/auth/me").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk());
    }
}
