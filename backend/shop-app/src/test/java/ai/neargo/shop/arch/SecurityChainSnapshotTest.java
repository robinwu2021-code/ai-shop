package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 认证链的快照：每条链匹配什么、过滤器按什么顺序排，以及各端在没带令牌、带错端令牌时回什么
 * （TDD-网关层与统一权限-开发计划 M0 任务 0.3）。
 *
 * <p>端点快照（{@link EndpointSnapshotTest}）管「有哪些入口、各要什么权限」，这一份管入口前面那一段。
 * 重构要把一个配置类里的几条链拆到三端各自的装配里，链少一条、过滤器换个顺序都不会让编译失败，
 * 只会让某一端的行为悄悄不同。比对方式见 {@link GatewaySnapshot}。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("网关重构 · 认证链快照")
class SecurityChainSnapshotTest {

    /** 每个前缀各探一个地址：三端、支付运营面、机器入口。 */
    private static final List<String> PROBED_PREFIXES = List.of("/mp/", "/biz/", "/ops/", "/pay-ops/", "/internal/");
    private static final Pattern IDENTITY_HASH = Pattern.compile("@[0-9a-f]{5,}");
    private static final Pattern LAMBDA_SUFFIX = Pattern.compile("\\$\\$Lambda.*");
    private static final Pattern ENVELOPE_CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");

    @Autowired
    WebApplicationContext context;
    @Autowired
    FilterChainProxy filterChainProxy;
    @Autowired
    TokenStore tokenStore;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    @DisplayName("每条链的匹配条件与过滤器顺序落盘；给了基线就逐行比对")
    void chainsMatchBaseline() {
        List<String> lines = new ArrayList<>();
        int index = 0;
        for (SecurityFilterChain chain : filterChainProxy.getFilterChains()) {
            String matcher = chain instanceof DefaultSecurityFilterChain d ? d.getRequestMatcher().toString() : "?";
            lines.add("chain " + index++ + " | " + stable(matcher));
            chain.getFilters().forEach(filter -> lines.add("    " + stable(filter.getClass().getName())));
        }

        assertThat(lines).as("一条链都没取到，说明取数方式失效了").isNotEmpty();
        for (String filter : List.of("MerchantTokenAuthFilter", "ConsumerTokenAuthFilter", "OperatorTokenAuthFilter")) {
            assertThat(lines).as("三端各自的令牌过滤器都应出现在快照里：%s", filter).anyMatch(l -> l.endsWith(filter));
        }

        GatewaySnapshot.writeAndCompare("security-chains.txt", lines);
    }

    @Test
    @DisplayName("各端在没带令牌、带各端令牌时的状态落盘；给了基线就逐行比对")
    void probesMatchBaseline() throws Exception {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("anonymous", null);
        tokens.put("consumer", tokenStore.issue(TokenStore.SessionData.of(LoginUser.consumer("SNAPSHOT-C", "snapshot"))));
        tokens.put("merchant", tokenStore.issue(TokenStore.SessionData.of(LoginUser.merchant("SNAPSHOT-B", "snapshot"))));
        tokens.put("operator", tokenStore.issue(TokenStore.SessionData.of(
                LoginUser.operator("SNAPSHOT-O", "snapshot", List.of(), List.of()))));

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        List<String> lines = new ArrayList<>();
        for (String prefix : PROBED_PREFIXES) {
            String url = firstPlainGet(prefix);
            if (url == null) {
                lines.add("GET " + prefix + "** | 没有不带路径变量的 GET 端点");
                continue;
            }
            for (var token : tokens.entrySet()) {
                var request = MockMvcRequestBuilders.get(url).accept(MediaType.APPLICATION_JSON);
                if (token.getValue() != null) {
                    request.header("Authorization", "Bearer " + token.getValue());
                }
                var response = mvc.perform(request).andReturn().getResponse();
                Matcher code = ENVELOPE_CODE.matcher(response.getContentAsString());
                lines.add("GET " + url + " | " + token.getKey() + " | http " + response.getStatus()
                        + (code.find() ? " code " + code.group(1) : ""));
            }
        }

        assertThat(lines).as("B 端与运营端不带令牌应当被拒，否则探测没有打到认证链上")
                .anyMatch(l -> l.contains("/biz/") && l.contains("| anonymous | http 401"))
                .anyMatch(l -> l.contains("/ops/") && l.contains("| anonymous | http 401"));

        GatewaySnapshot.writeAndCompare("security-probes.txt", lines);
    }

    /** 该前缀下按字典序第一个不带路径变量、只产出 JSON 的 GET 端点。 */
    private String firstPlainGet(String prefix) {
        TreeSet<String> candidates = new TreeSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null || !info.getMethodsCondition().getMethods().contains(RequestMethod.GET)) {
                return;
            }
            boolean streams = info.getProducesCondition().getProducibleMediaTypes().stream()
                    .anyMatch(MediaType.TEXT_EVENT_STREAM::isCompatibleWith);
            if (streams) {
                return;
            }
            patterns.getPatternValues().stream()
                    .filter(p -> p.startsWith(prefix) && !p.contains("{") && !p.contains("*"))
                    .forEach(candidates::add);
        });
        return candidates.isEmpty() ? null : candidates.first();
    }

    /** 去掉每次运行都会变的部分：对象的身份哈希、Lambda 类名的后缀。 */
    private static String stable(String text) {
        return LAMBDA_SUFFIX.matcher(IDENTITY_HASH.matcher(text).replaceAll("")).replaceAll("\\$\\$Lambda");
    }
}
