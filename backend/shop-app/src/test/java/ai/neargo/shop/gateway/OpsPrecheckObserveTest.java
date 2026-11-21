package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 运营端提前判权开到「观察」档：只记日志，对外行为与不开时相同（开发计划 M4 任务 4.11）。 */
@SpringBootTest(properties = "shop.gateway.ops.precheck=observe")
@ActiveProfiles("test")
@DisplayName("网关 · 运营端提前判权（观察）")
class OpsPrecheckObserveTest {

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");

    @Autowired
    WebApplicationContext context;
    @Autowired
    TokenStore tokenStore;

    @Test
    @DisplayName("★★ 观察档不拦：没权限且请求体不合法时，先看到的仍是参数错误（今天的顺序）")
    void observeDoesNotChangeTheResponse() throws Exception {
        String operator = tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.operator("OBSERVE-O", "observe", List.of(), List.of())));
        var body = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity())
                .build().perform(MockMvcRequestBuilders.post("/ops/payments/refunds/R-OBSERVE/review")
                        .header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{ 这不是合法的 JSON"))
                .andReturn().getResponse().getContentAsString();
        Matcher m = CODE.matcher(body);
        assertThat(m.find()).isTrue();
        assertThat(Integer.parseInt(m.group(1))).as("观察档下请求照常走到参数解析").isNotEqualTo(10403);
    }
}
