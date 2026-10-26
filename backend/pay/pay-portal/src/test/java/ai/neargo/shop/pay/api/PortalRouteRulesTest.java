package ai.neargo.shop.pay.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入层 controller 的落点与前缀（TDD-支付服务-I0地基 AC3；ADR-044 §2-3）。
 *
 * <p>今天模块里一个 controller 都没有 —— 所以先用正反例证明规则本身量得出东西，
 * 再扫真实 controller；否则「零违规」与「什么都没查」长得一模一样。
 */
class PortalRouteRulesTest {

    @Test
    @DisplayName("★★★ 入口包与前缀一一对应：放对包、映射到本入口前缀下才算合规")
    void acceptsControllerUnderOwnPrefix() {
        assertThat(PortalRouteRules.violations("X", "ai.neargo.shop.pay.api.ops", new String[] {"/pay-ops/v1/refunds"}))
                .isEmpty();
        assertThat(PortalRouteRules.violations("X", "ai.neargo.shop.pay.api.ops.refund", new String[] {"/pay-ops/v1"}))
                .as("子包同属该入口，等于前缀本身也合规")
                .isEmpty();
    }

    @Test
    @DisplayName("★★★ 放在 open 包却映射到商家前缀 —— 会被商家会话那条链放行，作者以为自己写的是验签接口")
    void rejectsCrossFacePrefix() {
        assertThat(PortalRouteRules.violations("X", "ai.neargo.shop.pay.api.open", new String[] {"/biz/pay/orders"}))
                .singleElement().asString().contains("OPEN").contains("/open/pay/v1");
    }

    @Test
    @DisplayName("★★ 前缀按路径段比，不按字符串开头：/biz/payment 不属于 /biz/pay")
    void prefixIsSegmentAware() {
        assertThat(PortalFace.BIZ.owns("/biz/pay")).isTrue();
        assertThat(PortalFace.BIZ.owns("/biz/pay/refunds")).isTrue();
        assertThat(PortalFace.BIZ.owns("/biz/payment")).isFalse();
    }

    @Test
    @DisplayName("★★ 不在任何入口子包、或没有类级映射，都算违规")
    void rejectsStrayPackageAndMissingClassMapping() {
        assertThat(PortalRouteRules.violations("X", "ai.neargo.shop.pay.api", new String[] {"/pay-ops/v1"}))
                .singleElement().asString().contains("不在任何入口子包下");
        assertThat(PortalRouteRules.violations("X", "ai.neargo.shop.pay.api.checkout", new String[0]))
                .singleElement().asString().contains("没有类级 @RequestMapping");
    }

    @Test
    @DisplayName("★ 判据读的是运行时注解值：常量写的路径也查得到")
    void readsResolvedAnnotationValues() {
        assertThat(PortalRouteRules.violations(ConstantMappedController.class))
                .as("本测试类在 api 根包，不属于任何入口 —— 规则必须认出来")
                .singleElement().asString().contains("不在任何入口子包下");
        assertThat(PortalRouteRules.violations(String.class)).as("非 controller 不查").isEmpty();
    }

    @Test
    @DisplayName("★★★ 本模块真实 controller 全部合规（今天为 0 个，I5 起逐个进来）")
    void everyRealControllerComplies() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> offenders = new ArrayList<>();
        int controllers = 0;
        for (var candidate : scanner.findCandidateComponents(PortalFace.API_PACKAGE)) {
            Class<?> type = ClassUtils.forName(candidate.getBeanClassName(), getClass().getClassLoader());
            if (type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("/test-classes/")) {
                continue; // 本测试里的样例 controller
            }
            controllers++;
            offenders.addAll(PortalRouteRules.violations(type));
        }
        assertThat(offenders).as("这些接入层 controller 落点或前缀不对：%s", offenders).isEmpty();
        assertThat(controllers).as("I0 只立骨架；有 controller 进来后这一行改成 isPositive()").isGreaterThanOrEqualTo(0);
    }

    private static final String OPS_PREFIX = "/pay-ops/v1";

    @RestController
    @RequestMapping(OPS_PREFIX + "/sample")
    static class ConstantMappedController {
    }
}
