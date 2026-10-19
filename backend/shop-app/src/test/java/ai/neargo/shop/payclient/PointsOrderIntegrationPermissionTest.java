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
import ai.neargo.shop.trade.order.points.OrderPointsBindingService;
import ai.neargo.shop.trade.order.points.OrderPointsRecoveryService;

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
            controller.obligations("ST_A");
            assertThatThrownBy(() -> controller.change(request("ST_A")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.policy("ST_B", "ONLINE"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.obligations("ST_B")).isInstanceOf(AccessDeniedException.class);
            auth(Perms.FINANCE_SETTLE_EXECUTE);
            assertThatThrownBy(() -> controller.obligations("ST_A")).isInstanceOf(AccessDeniedException.class);
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

    @Test
    void source_inspection_requires_read_permission_and_original_store_scope() {
        try (var ctx = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = ctx.getBean(OpsOrderPointsIntegrationController.class);
            var bindings = ctx.getBean(OrderPointsBindingService.class);
            var recovery = ctx.getBean(OrderPointsRecoveryService.class);
            var refunds = ctx.getBean(ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.class);
            when(bindings.find("S_A")).thenReturn(binding("S_A", "ST_A"));
            when(bindings.find("S_B")).thenReturn(binding("S_B", "ST_B"));
            auth(Perms.FINANCE_SETTLE_READ);
            controller.inspect("S_A");
            controller.manualRefunds("ST_A", "", 20);
            assertThatThrownBy(() -> controller.inspect("S_B"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.manualRefunds("ST_B", "", 20))
                    .isInstanceOf(AccessDeniedException.class);
            auth(Perms.FINANCE_SETTLE_EXECUTE);
            assertThatThrownBy(() -> controller.inspect("S_A"))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.manualRefunds("ST_A", "", 20))
                    .isInstanceOf(AccessDeniedException.class);
            verify(recovery).inspect("S_A");
            verify(refunds).manualForStore("ST_A", "", 20);
            verifyNoMoreInteractions(recovery);
        }
    }

    private OrderPointsBindingService.Binding binding(String sub, String store) {
        var admission = new OrderPointsBindingService.Admission(
                "REQ", "O", sub, "U", "M_A", store, "TYPE", "CNY", "ONLINE", 0,
                true, true, null);
        return new OrderPointsBindingService.Binding(
                "B", admission, OrderPointsBindingService.Phase.BOUND, 0, "H");
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
        OrderPointsBindingService bindings() {
            return mock(OrderPointsBindingService.class);
        }

        @Bean
        OrderPointsRecoveryService recovery() {
            return mock(OrderPointsRecoveryService.class);
        }

        @Bean
        ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator refunds() {
            return mock(ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.class);
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
                PointsIntegrationPolicyService policy, StoreDirectoryPort stores,
                OrderPointsBindingService bindings, OrderPointsRecoveryService recovery,
                ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator refunds) {
            return new OpsOrderPointsIntegrationController(
                    new OrderPointsIntegrationAppServiceImpl(policy, stores, bindings, recovery,
                            refunds,
                            mock(ai.neargo.shop.trade.service.AfterSaleService.class),
                            mock(ai.neargo.shop.trade.order.points.OrderPointsCoordinator.class)));
        }
    }
}
