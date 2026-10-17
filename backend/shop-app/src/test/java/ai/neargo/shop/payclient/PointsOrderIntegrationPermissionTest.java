package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.payclient.impl.OrderPointsIntegrationAppServiceImpl;
import ai.neargo.shop.portal.ops.pay.OpsOrderPointsIntegrationController;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.*;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

class PointsOrderIntegrationPermissionTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void auth(String perm) {
        var user =
                LoginUser.operator(
                        "OPS_A",
                        "QA",
                        List.of(),
                        List.of(perm),
                        DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("M_A")));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                user, null, List.of(new SimpleGrantedAuthority(perm))));
    }

    private IntegrationPolicyChange request(String store) {
        return new IntegrationPolicyChange(
                "ID", store, "ONLINE", Mode.ACTIVE, true, false, -1, "QA");
    }

    @Test
    void read_write_permissions_and_store_ownership_are_both_enforced() {
        try (var ctx = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = ctx.getBean(OpsOrderPointsIntegrationController.class);
            var domain = ctx.getBean(PointsIntegrationPolicyService.class);
            auth(Perms.FINANCE_SETTLE_READ);
            controller.policy("ST_A", "ONLINE");
            assertThatThrownBy(() -> controller.change(request("ST_A")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.policy("ST_B", "ONLINE"))
                    .isInstanceOf(AccessDeniedException.class);
            auth(Perms.FINANCE_SETTLE_EXECUTE);
            assertThatThrownBy(() -> controller.policy("ST_A", "ONLINE"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.change(request("ST_B")))
                    .isInstanceOf(AccessDeniedException.class);
            controller.change(request("ST_A"));
            verify(domain).policy("ST_A", "ONLINE");
            verify(domain).change(request("ST_A"), "OPS_A");
            verifyNoMoreInteractions(domain);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean
        PointsOpsPermissionTest.PermissionCheck perm() {
            return new PointsOpsPermissionTest.PermissionCheck();
        }

        @Bean
        PointsIntegrationPolicyService policy() {
            return mock(PointsIntegrationPolicyService.class);
        }

        @Bean
        StoreDirectoryPort stores() {
            var port = mock(StoreDirectoryPort.class);
            when(port.cards(List.of("ST_A")))
                    .thenReturn(
                            Map.of(
                                    "ST_A",
                                    new StoreDirectoryPort.StoreCard(
                                            "ST_A", "QA", "M_A", null, "ACTIVE", true, null, null,
                                            null, null, 0, 0)));
            return port;
        }

        @Bean
        OpsOrderPointsIntegrationController controller(
                PointsIntegrationPolicyService policy, StoreDirectoryPort stores) {
            return new OpsOrderPointsIntegrationController(
                    new OrderPointsIntegrationAppServiceImpl(policy, stores));
        }
    }
}
