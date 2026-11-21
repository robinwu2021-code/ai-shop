package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.gateway.ops.PayOpsPassThrough;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 开关默认关（TDD-支付运营端经网关转发与权限下沉 §2.3）：不装透传，该前缀回 404。用默认测试上下文，不另起容器。 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("网关 · 支付运营接口透传默认关")
class PayOpsPassThroughOffTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    ApplicationContext beans;
    @Autowired
    TokenStore tokenStore;

    @Test
    @DisplayName("开关关：没有透传 bean，登录后访问该前缀是 404")
    void offByDefault() throws Exception {
        assertThat(beans.getBeanProvider(PayOpsPassThrough.class).getIfAvailable()).isNull();
        String token = tokenStore.issue(TokenStore.SessionData.of(LoginUser.operator("PASS-OFF", "off", List.of(), List.of())));
        var response = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build()
                .perform(MockMvcRequestBuilders.get("/ops/pay-ops/v2/merchants").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
    }
}
