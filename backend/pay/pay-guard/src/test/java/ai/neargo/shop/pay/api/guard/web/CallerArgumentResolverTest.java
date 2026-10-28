package ai.neargo.shop.pay.api.guard.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.PortalPrincipal;
import ai.neargo.shop.pay.api.guard.core.PortalRequests;
import ai.neargo.shop.pay.api.guard.open.service.AppAuthService;
import ai.neargo.shop.pay.channel.api.CallerType;
import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * controller 经参数拿 {@link PayCaller}（开发计划 M1.5）：由守卫认出的调用方换成支付域的调用方
 * （开放 API：启用 → 授权门店，scope 守卫已判 —— M3.3；运营：运营操作员）。
 */
class CallerArgumentResolverTest {

    /** 几种参数写法 */
    @SuppressWarnings("unused")
    static class Endpoints {
        void create(@CallerGrant(grant = "COLLECT") PayCaller caller) {
        }

        void unannotated(PayCaller caller) {
        }

        void other(String notACaller) {
        }
    }

    /** 记下开放 API 服务被调用的顺序 */
    private static final class RecordingApps implements AppAuthService {
        final List<String> calls = new ArrayList<>();

        @Override
        public Result authenticate(SignedRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PayApp requireActive(String appNo) {
            calls.add("requireActive:" + appNo);
            PayApp a = new PayApp();
            a.setId(7L);
            a.setAppNo(appNo);
            return a;
        }

        @Override
        public PayCaller callerFor(PayApp app, String grantAction) {
            calls.add("callerFor:" + grantAction);
            return new PayCaller(CallerType.PARTNER, app.getId(), Set.of(11L), "APP:" + app.getAppNo(), null, null);
        }

        @Override
        public void checkDailyRefund(PayApp app, long amountMinor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkDailyRefund(Long payAppId, long amountMinor) {
            throw new UnsupportedOperationException();
        }
    }

    private final RecordingApps apps = new RecordingApps();
    private final CallerArgumentResolver resolver = new CallerArgumentResolver(providerOf(apps),
            new InternalCallers(null));

    private static org.springframework.beans.factory.ObjectProvider<AppAuthService> providerOf(AppAuthService apps) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("apps", apps);
        return beans.getBeanProvider(AppAuthService.class);
    }

    private static MethodParameter param(String method, Class<?> type) throws Exception {
        Method m = Endpoints.class.getDeclaredMethod(method, type);
        return new MethodParameter(m, 0);
    }

    private static ServletWebRequest requestAs(PortalPrincipal who) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (who != null) {
            req.setAttribute(PortalRequests.PRINCIPAL, who);
        }
        return new ServletWebRequest(req);
    }

    @Test
    @DisplayName("★★★ 开放 API：按注解的授权动作换成调用方（启用 → 授权门店）；scope 不在这里判（守卫已判，M3.3）")
    void openUsesDeclaredScopeAndGrant() throws Exception {
        PortalPrincipal app = new PortalPrincipal(PortalFace.OPEN, "APP", "APP7", null, Set.of());

        Object caller = resolver.resolveArgument(param("create", PayCaller.class), null, requestAs(app), null);

        assertThat(apps.calls).containsExactly("requireActive:APP7", "callerFor:COLLECT");
        assertThat(caller).isInstanceOfSatisfying(PayCaller.class, c -> {
            assertThat(c.type()).isEqualTo(CallerType.PARTNER);
            assertThat(c.callerId()).isEqualTo(7L);
            assertThat(c.storeScope()).containsExactly(11L);
        });
    }

    @Test
    @DisplayName("★★★ 运营端：运营操作员（平台身份、不按门店圈），操作员号就是守卫认出的员工号")
    void opsIsTheOperator() throws Exception {
        PortalPrincipal op = new PortalPrincipal(PortalFace.OPS, "OPERATOR", "U-OPS-1", null, Set.of("pay:order:view"));

        Object caller = resolver.resolveArgument(param("unannotated", PayCaller.class), null, requestAs(op), null);

        assertThat(caller).isInstanceOfSatisfying(PayCaller.class, c -> {
            assertThat(c.type()).isEqualTo(CallerType.OPS);
            assertThat(c.operatorNo()).isEqualTo("U-OPS-1");
            assertThat(c.storeScope()).isEmpty();
        });
        assertThat(apps.calls).isEmpty();
    }

    @Test
    @DisplayName("★★ 只解析 PayCaller 参数")
    void supportsOnlyPayCaller() throws Exception {
        assertThat(resolver.supportsParameter(param("create", PayCaller.class))).isTrue();
        assertThat(resolver.supportsParameter(param("other", String.class))).isFalse();
    }

    @Test
    @DisplayName("★★ 开放 API 的 PayCaller 参数没写 @CallerGrant、守卫没认出人、入口不支持参数注入 —— 都是写错了，直接失败")
    void misuseFailsLoudly() throws Exception {
        PortalPrincipal app = new PortalPrincipal(PortalFace.OPEN, "APP", "APP7", null, Set.of());
        assertThatThrownBy(() -> resolver.resolveArgument(param("unannotated", PayCaller.class), null, requestAs(app), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("@CallerGrant");

        assertThatThrownBy(() -> resolver.resolveArgument(param("create", PayCaller.class), null, requestAs(null), null))
                .isInstanceOf(IllegalStateException.class);

        PortalPrincipal visitor = new PortalPrincipal(PortalFace.CHECKOUT, "VISITOR", "1.2.3.4", null, Set.of());
        assertThatThrownBy(() -> resolver.resolveArgument(param("unannotated", PayCaller.class), null, requestAs(visitor), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CHECKOUT");
    }
}
