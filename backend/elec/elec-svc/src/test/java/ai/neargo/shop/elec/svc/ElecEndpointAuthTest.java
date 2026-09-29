package ai.neargo.shop.elec.svc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * <b>每一个 /elec 端点都要表态：要么在下面的匿名名单里，要么没登录就 401。</b>
 *
 * <p>为什么要这道闸：{@code ElecSecurityConfig} 的链是 {@code anyRequest().permitAll()} ——
 * 「要不要登录」不在链上挡，而是各接口自己取当前用户时决定。这个做法的代价是
 * <b>忘了取用户的新接口不会报错，它会当成游客把数据发出去</b>，而且本地跑、界面看、
 * 灰度用都正常。所以把「表态」搬到这里：新端点进来，要么改名单，要么这条红。
 *
 * <p>探测用的是真实请求链（带安全过滤器），不是读源码 —— 读源码认不出
 * 「调了 currentUserNo 但结果没用上」这种写法。
 */
@SpringBootTest(classes = ElecApplication.class)
@ActiveProfiles("test")
@Import(FakeMainSystem.Config.class)
class ElecEndpointAuthTest {

    /**
     * 游客可用的端点。<b>只有查料号在里面</b>：不登录就能搜，是这门生意的入口，
     * 逼人先登录等于把来看行情的人挡在门外（见 TDD-元器件-独立服务与第一步 §3）。
     *
     * <p>往里加一条之前先答一句：这个接口回的东西，<b>发给不认识的人也没关系吗</b>？
     */
    private static final Set<String> ANON = Set.of(
            "GET /elec/c/part",
            "GET /elec/c/part/lookup",
            "GET /elec/c/part/{partNo}");

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("★★★ 没登录时：匿名名单外的每一个 /elec 端点都回 401")
    void everyEndpointDeclaresItself() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();

        Set<String> endpoints = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> {
            for (String pattern : patterns(info)) {
                if (!pattern.startsWith("/elec/")) continue;
                info.getMethodsCondition().getMethods()
                        .forEach(m -> endpoints.add(m.name() + " " + pattern));
            }
        });
        assertThat(endpoints).as("一个端点都没扫到 —— 扫描面塌了，这道闸就永远是绿的").hasSizeGreaterThan(15);

        List<String> leaked = new java.util.ArrayList<>();
        List<String> unreachable = new java.util.ArrayList<>();
        for (String ep : endpoints) {
            String[] parts = ep.split(" ", 2);
            // 路径变量一律填 "1"：字符串收得下，int 也转得动 —— 参数转换失败会在方法之前 400，
            // 那样探不出它要不要登录（不是 401 也不是 200，看起来像「挡住了」）
            String url = parts[1].replaceAll("\\{[^}]+}", "1");
            MockHttpServletRequestBuilder req = request(org.springframework.http.HttpMethod.valueOf(parts[0]), url)
                    .contentType(MediaType.APPLICATION_JSON).content("{}");
            MvcResult res = mvc.perform(req).andReturn();
            int code = res.getResponse().getStatus();
            if (ANON.contains(ep)) {
                if (code == 401) unreachable.add(ep + " → 401");
            } else if (code != 401) {
                leaked.add(ep + " → " + code);
            }
        }

        assertThat(leaked).as("""
                这些端点没登录也能进。多半是忘了 SecurityUtils.currentUserNo() / ElecOpsGuard.require()——
                链上是 permitAll，少这一句不会报错，只会把别人的数据发给游客。
                确实该对游客开放的，写进本测试的 ANON 并说明为什么""").isEmpty();
        assertThat(unreachable).as("名单里说匿名可用，实际回了 401 —— 名单与实现对不上").isEmpty();
    }

    private static Set<String> patterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            Set<String> out = new TreeSet<>();
            info.getPathPatternsCondition().getPatterns().forEach(p -> out.add(p.getPatternString()));
            return out;
        }
        return info.getPatternValues();
    }
}
