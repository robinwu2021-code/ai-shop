package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.ResourceIndex;
import ai.neargo.shop.gateway.core.resource.ResourceIndexes;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 运营端提前判权开到「拦截」档（TDD-网关层与统一权限 AC21、AC22；开发计划 M4）。
 */
@SpringBootTest(properties = "shop.gateway.ops.precheck=enforce")
@ActiveProfiles("test")
@DisplayName("网关 · 运营端提前判权（拦截）")
class OpsPrecheckEnforceTest {

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
    private static final String REVIEW = "/ops/payments/refunds/R-PRECHECK/review";

    @Autowired
    WebApplicationContext context;
    @Autowired
    TokenStore tokenStore;
    @Autowired
    ResourceIndexes indexes;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    @DisplayName("★★★ 索引是全的：运营端与支付运营面每个已登记的端点各有一行；提前判不了的只有个位数")
    void indexCoversEveryOpsEndpoint() {
        for (String prefix : List.of("/ops", "/pay-ops")) {
            ResourceIndex index = indexes.of(List.of(prefix));
            // 同一个「方法 + 地址」登记了多个处理方法时索引里并成一行，所以按去重后的键数比
            java.util.Set<String> registered = new java.util.TreeSet<>();
            mapping.getHandlerMethods().keySet().stream()
                    .filter(info -> info.getPathPatternsCondition() != null)
                    .forEach(info -> info.getPathPatternsCondition().getPatternValues().stream()
                            .filter(p -> p.startsWith(prefix + "/"))
                            .forEach(p -> {
                                var verbs = info.getMethodsCondition().getMethods();
                                if (verbs.isEmpty()) {
                                    registered.add("* " + p);
                                }
                                verbs.forEach(verb -> registered.add(verb.name() + " " + p));
                            }));
            assertThat(index.all().stream().map(r -> r.method() + " " + r.pattern()).toList())
                    .as("%s 下索引的键应等于 Spring MVC 登记的端点", prefix)
                    .containsExactlyInAnyOrderElementsOf(registered);
        }
        ResourceIndex ops = indexes.of(List.of("/ops"));
        assertThat(ops.all()).as("运营端绝大多数端点带权限式").filteredOn(r -> r.access() == Access.POINT)
                .hasSizeGreaterThan(ops.all().size() / 2);
        assertThat(ops.all()).as("提前判不了、留给方法判的应当极少；变多说明解析器漏认了一种写法")
                .filteredOn(r -> r.access() == Access.DEFER).hasSizeLessThanOrEqualTo(5);

        ApiResource review = ops.locate("POST", REVIEW).orElseThrow();
        assertThat(review.pattern()).isEqualTo("/ops/payments/refunds/{payRefundNo}/review");
        assertThat(review.rule().codes()).containsExactly("pay:refund:review");
    }

    @Test
    @DisplayName("★★★ 没权限且请求体不合法：得到「没有操作权限」而不是参数错误 —— 说明拦在读请求体之前")
    void deniedBeforeTheBodyIsRead() throws Exception {
        String operator = tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.operator("PRECHECK-O", "precheck", List.of(), List.of())));
        var response = mvc().perform(MockMvcRequestBuilders.post(REVIEW)
                        .header("Authorization", "Bearer " + operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{ 这不是合法的 JSON"))
                .andReturn().getResponse();
        assertThat(code(response.getContentAsString())).isEqualTo(10403);
    }

    @Test
    @DisplayName("不该被波及的：公开入口照常进到入口方法；没登录的仍是 401")
    void publicAndAnonymousAreUntouched() throws Exception {
        var login = mvc().perform(MockMvcRequestBuilders.post("/ops/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();
        assertThat(code(login.getContentAsString())).as("登录入口被提前判权拦了").isNotEqualTo(10403);

        assertThat(mvc().perform(MockMvcRequestBuilders.post(REVIEW)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    private static int code(String body) {
        Matcher m = CODE.matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
