package ai.neargo.shop.platform.perm.impl;

import ai.neargo.shop.platform.perm.entity.SysFunctionPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 菜单状态要按**运行时开关**降级（TDD-ops-功能开关与菜单状态 §1 的 AC1 / AC2）。
 *
 * <p>起因：{@code /merchants?tab=chain} 在菜单里是个正常项，点进去 404。
 * 整个进销存域被 {@code shop.inventory.enabled=false} 关着，关着时**一个 Bean 都不装**、
 * 控制器根本不注册；而这个点在库里标着 {@code IMPLEMENTED} ——
 * 那是**源码事实**（生成器按「源码里有没有端点」算），算不到运行时开关。
 * 运营看到的是一个和别的项一模一样的入口，点下去 404，
 * 而他没有任何线索知道这是「没开」而不是「坏了」。
 *
 * <p><b>为什么直接测 {@code effectiveStatus} 而不是起上下文打 /ops/menu</b>：
 * 这一条的全部逻辑就是「读属性 → 决定返回哪个状态」，
 * 起一个 Spring 上下文只会让它慢，且失败时指不到这一行。
 * 端到端那一层由场景测试覆盖。
 */
class GatedMenuStatusTest {

    private static SysFunctionPoint point(String status, String gatedBy) {
        SysFunctionPoint p = new SysFunctionPoint();
        p.setPointCode("OPS_MERCHANT__TAB_CHAIN");
        p.setBackendStatus(status);
        p.setGatedBy(gatedBy);
        return p;
    }

    private static PermConfigServiceImpl svc(MockEnvironment env) {
        return new PermConfigServiceImpl(null, null, null, null, null, null, null, null, null, null, env);
    }

    @Test
    @DisplayName("AC1 开关关着：IMPLEMENTED 降级成 NOT_IMPLEMENTED（端上按「待建」灰显）")
    void gatedOffDowngrades() {
        MockEnvironment env = new MockEnvironment().withProperty("shop.inventory.enabled", "false");
        assertThat(svc(env).effectiveStatus(point("IMPLEMENTED", "shop.inventory.enabled")))
                .isEqualTo("NOT_IMPLEMENTED");
    }

    @Test
    @DisplayName("AC1 属性整个没配：同样降级 —— 没配就是没开，与 @ConditionalOnProperty 一致")
    void gatedMissingDowngrades() {
        // 后端那边没写 matchIfMissing，缺失即不装 Bean；这里必须同口径，
        // 否则「本地没配」会让菜单显示成可用而实际 404
        assertThat(svc(new MockEnvironment()).effectiveStatus(point("IMPLEMENTED", "shop.inventory.enabled")))
                .isEqualTo("NOT_IMPLEMENTED");
    }

    @Test
    @DisplayName("AC2 开关开着：原样返回，与普通项无差别")
    void gatedOnKeepsStatus() {
        MockEnvironment env = new MockEnvironment().withProperty("shop.inventory.enabled", "true");
        assertThat(svc(env).effectiveStatus(point("IMPLEMENTED", "shop.inventory.enabled")))
                .isEqualTo("IMPLEMENTED");
    }

    @Test
    @DisplayName("没登记开关的点不受影响 —— 这条规则不许扩大射程")
    void ungatedUntouched() {
        MockEnvironment env = new MockEnvironment();
        assertThat(svc(env).effectiveStatus(point("IMPLEMENTED", null))).isEqualTo("IMPLEMENTED");
        assertThat(svc(env).effectiveStatus(point("NOT_IMPLEMENTED", null))).isEqualTo("NOT_IMPLEMENTED");
    }

    @Test
    @DisplayName("本来就 NOT_IMPLEMENTED 的，开关开着也不会被提升")
    void neverUpgrades() {
        MockEnvironment env = new MockEnvironment().withProperty("shop.inventory.enabled", "true");
        assertThat(svc(env).effectiveStatus(point("NOT_IMPLEMENTED", "shop.inventory.enabled")))
                .isEqualTo("NOT_IMPLEMENTED");
    }
}
