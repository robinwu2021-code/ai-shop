package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.points.query.PointsOverviewService.Overview;
import ai.neargo.shop.pay.points.query.PointsOverviewService.ReceivablePage;
import ai.neargo.shop.portal.ops.pay.OpsPointsController;
import ai.neargo.shop.portal.ops.pay.OpsPointsPolicyController;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;

class PointsOpsPermissionTest {
    @Test
    void both_queries_require_finance_read_permission_at_the_server() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(OpsPointsController.class);
            authenticate("customer:read");
            assertThatThrownBy(() -> controller.overview("T", "CN"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.receivables("T", "STORE", 0, 50))
                    .isInstanceOf(AccessDeniedException.class);
            authenticate(Perms.FINANCE_SETTLE_READ);
            assertThat(controller.overview("T", "CN").uMinor()).isEqualTo(100);
            assertThat(controller.receivables("T", "STORE", 0, 50).rows()).isEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void every_configuration_endpoint_checks_its_read_or_execute_permission() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(OpsPointsPolicyController.class);
            for (var method : OpsPointsPolicyController.class.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(GetMapping.class)
                        && !method.isAnnotationPresent(PostMapping.class)) continue;
                Object[] args =
                        Arrays.stream(method.getParameterTypes())
                                .map(
                                        t ->
                                                t == long.class
                                                        ? (Object) 0L
                                                        : t == int.class
                                                                ? 50
                                                                : t == String.class
                                                                        ? "SYNTHETIC"
                                                                        : null)
                                .toArray();
                authenticate("customer:read");
                assertThatThrownBy(() -> method.invoke(controller, args))
                        .isInstanceOf(InvocationTargetException.class)
                        .cause()
                        .isInstanceOf(AccessDeniedException.class);
                String required =
                        method.isAnnotationPresent(GetMapping.class)
                                ? Perms.FINANCE_SETTLE_READ
                                : Perms.FINANCE_SETTLE_EXECUTE;
                authenticate(required);
                method.invoke(controller, args);
                if (method.isAnnotationPresent(PostMapping.class)) {
                    authenticate(Perms.FINANCE_SETTLE_READ);
                    assertThatThrownBy(() -> method.invoke(controller, args))
                            .isInstanceOf(InvocationTargetException.class)
                            .cause()
                            .isInstanceOf(AccessDeniedException.class);
                }
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(String permission) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                LoginUser.operator(
                                        "SYNTHETIC_OPERATOR",
                                        "Synthetic",
                                        List.of(),
                                        List.of(),
                                        null),
                                "",
                                List.of(new SimpleGrantedAuthority(permission))));
    }

    public static class PermissionCheck {
        public boolean can(String permission) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null
                    && auth.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals(permission));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean
        PermissionCheck perm() {
            return new PermissionCheck();
        }

        @Bean
        OpsPointsPolicyController policyController() {
            return new OpsPointsPolicyController(
                    org.mockito.Mockito.mock(PointsConfigurationAppService.class));
        }

        @Bean
        OpsPointsController controller() {
            return new OpsPointsController(
                    new PointsOpsAppService() {
                        @Override
                        public Overview overview(String type, String market) {
                            return new Overview(
                                    type, market, "CNY", 2, 2, 1, 100, 0, 0, 200, 0, 0, 0, 0);
                        }

                        @Override
                        public ReceivablePage receivables(
                                String type, String store, long after, int limit) {
                            return new ReceivablePage(type, "CNY", 2, List.of(), after, false);
                        }
                    });
        }
    }
}
