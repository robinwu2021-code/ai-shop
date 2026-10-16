package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.pay.points.query.PointsOverviewService.Overview;
import ai.neargo.shop.pay.points.query.PointsOverviewService.ReceivablePage;
import ai.neargo.shop.portal.ops.pay.OpsPointsController;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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

    private void authenticate(String permission) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "SYNTHETIC_OPERATOR",
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
