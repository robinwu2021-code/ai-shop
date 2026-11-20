package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LivePermResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.proxy.DownstreamActions;
import ai.neargo.shop.gateway.core.proxy.GatewaySigning;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.PermRule;
import ai.neargo.shop.gateway.core.security.GatewayExchange;
import ai.neargo.shop.svc.ServiceName;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 网关调下游时签进去的「下游动作」（TDD-网关层与统一权限 §2.7，AC6；开发计划 §2.6 任务 14）。不起 Spring。
 *
 * <p>用一张假的映射，只看网关这一侧怎么算：判过的码里只认这个人确实持有的，按服务挑映射，去重排序。
 */
@DisplayName("网关 · 签进下游调用的动作")
class GatewayDownstreamActionsTest {

    /** 假映射：两个码各对应一个动作，另一个服务的映射不该混进来。 */
    private static DownstreamActions map(String service, Map<String, List<String>> table) {
        return new DownstreamActions() {
            @Override
            public String service() {
                return service;
            }

            @Override
            public List<String> actionsOf(Realm realm, Collection<String> points) {
                return points.stream().flatMap(code -> table.getOrDefault(code, List.of()).stream())
                        .distinct().sorted().toList();
            }
        };
    }

    private static GatewaySigning signing() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("pay", map(ServiceName.PAY,
                Map.of("pay:refund:review", List.of("REVIEW_REFUND"), "pay:ledger:read", List.of("READ_LEDGER", "QUERY"))));
        beans.registerSingleton("other", map("INVENTORY", Map.of("pay:refund:review", List.of("STOCK_MOVE"))));
        beans.registerSingleton("perm", new PermChecker(beans.getBeanProvider(LivePermResolver.class)));
        return new GatewaySigning("k", beans.getBeanProvider(DownstreamActions.class), beans.getBeanProvider(PermChecker.class));
    }

    private static void request(PermRule rule, List<String> held) {
        LoginUser user = LoginUser.operator("ST-1", "测试", List.of("R"), held);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ops/payments/refunds/R1/review");
        GatewayExchange.resource(request, new ApiResource("POST", "/ops/payments/refunds/{no}/review", ApiResource.LOCAL,
                Access.POINT, rule, "X#y"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("★★★ 规则是「a 或 b」、这个人只有 a：只换 a 的动作，不因为规则里出现过 b 就把 b 的动作也批了")
    void onlyHeldPointsBecomeActions() {
        request(PermRule.any(List.of(PermRule.code("pay:ledger:read"), PermRule.code("pay:refund:review"))),
                List.of("pay:ledger:read"));
        var caller = signing().caller(ServiceName.PAY);
        assertThat(caller.points()).containsExactly("pay:ledger:read", "pay:refund:review");
        assertThat(caller.actions()).containsExactly("QUERY", "READ_LEDGER");
    }

    @Test
    @DisplayName("★★ 运营端的通配权限照原有口径算持有；别的服务的映射不混进支付")
    void wildcardCountsAndServicesStayApart() {
        request(PermRule.code("pay:refund:review"), List.of("pay:*"));
        assertThat(signing().caller(ServiceName.PAY).actions()).containsExactly("REVIEW_REFUND");
        assertThat(signing().caller("INVENTORY").actions()).containsExactly("STOCK_MOVE");
    }

    @Test
    @DisplayName("★★ 没登录、或网关没判过码（只要登录的入口）：不带任何动作")
    void nothingJudgedMeansNoActions() {
        request(null, List.of("*"));
        assertThat(signing().caller(ServiceName.PAY).actions()).isEmpty();
        SecurityContextHolder.clearContext();
        assertThat(signing().caller(ServiceName.PAY).actions()).isEmpty();
    }
}
