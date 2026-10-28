package ai.neargo.shop.pay.api.guard.open;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.PortalActions;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 开放 API 的 scope 在守卫里判（开发计划 M3.3）：按动作要 scope，动作认不出就不放（fail closed）——
 * 新加一个端点忘了登记，结果是 403 而不是「谁都能调」。
 */
class AppScopesTest {

    private static String action(String method, String path) {
        return PortalActions.of(PortalFace.OPEN, method, path);
    }

    @Test
    @DisplayName("★★★ 每个开放 API 端点都映射到一个 scope")
    void everyEndpointHasAScope() {
        assertThat(AppScopes.requiredFor(action("POST", "/open/pay/v1/orders"))).isEqualTo("pay:create");
        assertThat(AppScopes.requiredFor(action("POST", "/open/pay/v1/orders/PYO1/pay"))).isEqualTo("pay:create");
        assertThat(AppScopes.requiredFor(action("POST", "/open/pay/v1/orders/PYO1/close"))).isEqualTo("pay:create");
        assertThat(AppScopes.requiredFor(action("GET", "/open/pay/v1/orders/PYO1"))).isEqualTo("pay:read");
        assertThat(AppScopes.requiredFor(action("GET", "/open/pay/v1/orders"))).isEqualTo("pay:read");
        assertThat(AppScopes.requiredFor(action("GET", "/open/pay/v1/stores/S1/pay-methods"))).isEqualTo("pay:read");
        assertThat(AppScopes.requiredFor(action("GET", "/open/pay/v1/refunds/PYR1"))).isEqualTo("pay:read");
        assertThat(AppScopes.requiredFor(action("POST", "/open/pay/v1/orders/PYO1/refunds"))).isEqualTo("pay:refund");
    }

    @Test
    @DisplayName("★★★ 认不出的动作没有 scope（守卫据此 403）")
    void unknownActionFailsClosed() {
        assertThat(AppScopes.requiredFor(action("DELETE", "/open/pay/v1/orders/PYO1"))).isNull();
        assertThat(AppScopes.requiredFor("READ_LEDGER")).isNull();
    }

    @Test
    @DisplayName("★★ 接入方的 scopes 是逗号分隔、可带空格；空或没有就是什么都没有")
    void granted() {
        PayApp app = new PayApp();
        app.setScopes("pay:create, pay:read");
        assertThat(AppScopes.granted(app, "pay:read")).isTrue();
        assertThat(AppScopes.granted(app, "pay:refund")).isFalse();
        app.setScopes(null);
        assertThat(AppScopes.granted(app, "pay:read")).isFalse();
    }
}
