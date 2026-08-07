package ai.neargo.shop.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约包与三条过滤器链的实弹验证。
 *
 * <p>为什么在 S0 就写它：c-app 的 {@code http-client.ts} 按 {@code {code,msg,data}} 硬解包，
 * 字段名写错（比如跟着 neargo 写成 {@code message}）不会有任何编译错误，
 * 只会在联调时表现为「所有接口都提示请求失败」。这个测试是那条契约的唯一守卫。
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiContractTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("响应包必须是 {code,msg,data}，且游客可拉启动配置")
    void bootstrapReturnsContractEnvelope() throws Exception {
        mockMvc().perform(get("/mp/config/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").exists())
                .andExpect(jsonPath("$.data.defaultSkin").value("fresh"))
                // 积分一期关闭（ADR-006），开关必须原样透出给端侧
                .andExpect(jsonPath("$.data.features.points").value(false));
    }

    @Test
    @DisplayName("未登录访问 /biz/** 一律 401（防线①：前缀 + 过滤器链）")
    void bizRequiresLogin() throws Exception {
        mockMvc().perform(get("/biz/order"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未登录访问 /ops/** 一律 401")
    void opsRequiresLogin() throws Exception {
        mockMvc().perform(get("/ops/order"))
                .andExpect(status().isUnauthorized());
    }
}
