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
    void compensated_refund_close_requires_settlement_execute_permission() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(
                    ai.neargo.shop.portal.ops.pay.OpsOrderPointsIntegrationController.class);
            var req = new ai.neargo.shop.portal.ops.pay.OpsOrderPointsIntegrationController.CloseRefundReq(
                    "reviewed compensation");
            authenticate(Perms.FINANCE_SETTLE_READ);
            assertThatThrownBy(() -> controller.closeRefund("AS-1", req))
                    .isInstanceOf(AccessDeniedException.class);
            authenticate(Perms.FINANCE_SETTLE_EXECUTE);
            controller.closeRefund("AS-1", req);
            var retry = new ai.neargo.shop.portal.ops.pay.OpsOrderPointsIntegrationController.RetryRefundReq(
                    "retry with new attempt");
            authenticate(Perms.FINANCE_SETTLE_READ);
            assertThatThrownBy(() -> controller.retryRefund("AS-1", retry))
                    .isInstanceOf(AccessDeniedException.class);
            authenticate(Perms.FINANCE_SETTLE_EXECUTE);
            controller.retryRefund("AS-1", retry);
            assertThatThrownBy(() -> controller.manualRefunds("ST_A", "", 20))
                    .isInstanceOf(AccessDeniedException.class);
            authenticate(Perms.FINANCE_SETTLE_READ);
            controller.manualRefunds("ST_A", "", 20);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
    @Test
    void preparing_auto_executable_payout_requires_payout_permission() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller =
                    context.getBean(
                            ai.neargo.shop.portal.ops.pay.OpsPointsWorkbenchController.class);
            var request =
                    new ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementChange(
                            "QA",
                            "T",
                            "STORE",
                            "S",
                            1,
                            ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementAction
                                    .PREPARE_PAYOUT,
                            "QA");
            authenticate(Perms.FINANCE_SETTLE_EXECUTE);
            assertThatThrownBy(() -> controller.changeStatement(request))
                    .isInstanceOf(AccessDeniedException.class);
            authenticate(Perms.FINANCE_PAYOUT_EXECUTE);
            controller.changeStatement(request);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

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
                if (method.getName().equals("saveFallback")) {
                    // This legacy endpoint is intentionally retired; authorization still runs first.
                    assertThatThrownBy(() -> method.invoke(controller, args))
                            .isInstanceOf(InvocationTargetException.class).cause()
                            .isInstanceOf(ai.neargo.shop.common.BizException.class);
                } else {
                    method.invoke(controller, args);
                }
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

    @Test
    void treasury_permissions_separate_making_checking_and_reading_at_every_endpoint()
            throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller =
                    context.getBean(
                            ai.neargo.shop.portal.ops.pay.OpsPointsTreasuryController.class);
            for (var method :
                    ai.neargo.shop.portal.ops.pay.OpsPointsTreasuryController.class
                            .getDeclaredMethods()) {
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
                String required =
                        method.isAnnotationPresent(GetMapping.class)
                                ? Perms.FINANCE_SETTLE_READ
                                : java.util.Set.of("review", "executeWithdrawal")
                                                .contains(method.getName())
                                        ? Perms.FINANCE_PAYOUT_EXECUTE
                                        : Perms.FINANCE_SETTLE_EXECUTE;
                for (String permission :
                        List.of(
                                Perms.FINANCE_SETTLE_READ,
                                Perms.FINANCE_SETTLE_EXECUTE,
                                Perms.FINANCE_PAYOUT_EXECUTE)) {
                    authenticate(permission);
                    if (permission.equals(required)) method.invoke(controller, args);
                    else
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

    @Test
    void workbench_permissions_protect_all_queries_and_recovery_actions() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller =
                    context.getBean(
                            ai.neargo.shop.portal.ops.pay.OpsPointsWorkbenchController.class);
            for (var method :
                    ai.neargo.shop.portal.ops.pay.OpsPointsWorkbenchController.class
                            .getDeclaredMethods()) {
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
                                                                        : t
                                                                                        == ai.neargo
                                                                                                .shop
                                                                                                .pay
                                                                                                .points
                                                                                                .job
                                                                                                .PointsRecoveryService
                                                                                                .Operation
                                                                                                .class
                                                                                ? ai.neargo.shop.pay
                                                                                        .points.job
                                                                                        .PointsRecoveryService
                                                                                        .Operation
                                                                                        .PAYOUT
                                                                                : null)
                                .toArray();
                String required =
                        method.isAnnotationPresent(GetMapping.class)
                                ? Perms.FINANCE_SETTLE_READ
                                : method.getName().equals("executeRecovery")
                                        ? Perms.FINANCE_PAYOUT_EXECUTE
                                        : Perms.FINANCE_SETTLE_EXECUTE;
                for (String permission :
                        List.of(
                                Perms.FINANCE_SETTLE_READ,
                                Perms.FINANCE_SETTLE_EXECUTE,
                                Perms.FINANCE_PAYOUT_EXECUTE)) {
                    authenticate(permission);
                    if (permission.equals(required)) method.invoke(controller, args);
                    else
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

    @Test
    void inspection_protects_read_freeze_and_reconciliation_permissions_independently()
            throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller =
                    context.getBean(
                            ai.neargo.shop.portal.ops.pay.OpsPointsInspectionController.class);
            for (var method :
                    ai.neargo.shop.portal.ops.pay.OpsPointsInspectionController.class
                            .getDeclaredMethods()) {
                if (!method.isAnnotationPresent(GetMapping.class)
                        && !method.isAnnotationPresent(PostMapping.class)) continue;
                Object[] args =
                        Arrays.stream(method.getParameterTypes())
                                .map(
                                        t ->
                                                t == String.class
                                                        ? "SYNTHETIC"
                                                        : t == long.class
                                                                ? (Object) 0L
                                                                : t == int.class
                                                                        ? 50
                                                                        : t
                                                                                        == java.time
                                                                                                .LocalDate
                                                                                                .class
                                                                                ? java.time
                                                                                        .LocalDate
                                                                                        .of(
                                                                                                2026,
                                                                                                10,
                                                                                                16)
                                                                                : t
                                                                                                == ai
                                                                                                        .neargo
                                                                                                        .shop
                                                                                                        .pay
                                                                                                        .points
                                                                                                        .model
                                                                                                        .HolderType
                                                                                                        .class
                                                                                        ? ai.neargo
                                                                                                .shop
                                                                                                .pay
                                                                                                .points
                                                                                                .model
                                                                                                .HolderType
                                                                                                .STORE
                                                                                        : t
                                                                                                        == ai
                                                                                                                .neargo
                                                                                                                .shop
                                                                                                                .pay
                                                                                                                .points
                                                                                                                .model
                                                                                                                .PositionMove
                                                                                                                .Kind
                                                                                                                .class
                                                                                                ? ai
                                                                                                        .neargo
                                                                                                        .shop
                                                                                                        .pay
                                                                                                        .points
                                                                                                        .model
                                                                                                        .PositionMove
                                                                                                        .Kind
                                                                                                        .GRANT
                                                                                                : t
                                                                                                                == ai
                                                                                                                        .neargo
                                                                                                                        .shop
                                                                                                                        .pay
                                                                                                                        .points
                                                                                                                        .admin
                                                                                                                        .PointsInspectionService
                                                                                                                        .StatsScope
                                                                                                                        .class
                                                                                                        ? ai
                                                                                                                .neargo
                                                                                                                .shop
                                                                                                                .pay
                                                                                                                .points
                                                                                                                .admin
                                                                                                                .PointsInspectionService
                                                                                                                .StatsScope
                                                                                                                .STORE
                                                                                                        : null)
                                .toArray();
                String required =
                        method.getName().equals("reconciliation")
                                ? Perms.FINANCE_RECON_READ
                                : method.getName().equals("reconcile")
                                        ? Perms.FINANCE_RECON_RESOLVE
                                        : method.isAnnotationPresent(GetMapping.class)
                                                ? Perms.FINANCE_SETTLE_READ
                                                : Perms.FINANCE_SETTLE_EXECUTE;
                for (String permission :
                        List.of(
                                Perms.FINANCE_SETTLE_READ,
                                Perms.FINANCE_SETTLE_EXECUTE,
                                Perms.FINANCE_PAYOUT_EXECUTE,
                                Perms.FINANCE_RECON_READ,
                                Perms.FINANCE_RECON_RESOLVE)) {
                    authenticate(permission);
                    if (permission.equals(required)) method.invoke(controller, args);
                    else
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
        ai.neargo.shop.portal.ops.pay.OpsOrderPointsIntegrationController integrationController() {
            return new ai.neargo.shop.portal.ops.pay.OpsOrderPointsIntegrationController(
                    org.mockito.Mockito.mock(OrderPointsIntegrationAppService.class));
        }

        @Bean
        ai.neargo.shop.portal.ops.pay.OpsPointsInspectionController inspectionController() {
            return new ai.neargo.shop.portal.ops.pay.OpsPointsInspectionController(
                    org.mockito.Mockito.mock(PointsInspectionAppService.class));
        }

        @Bean
        ai.neargo.shop.portal.ops.pay.OpsPointsWorkbenchController workbenchController() {
            return new ai.neargo.shop.portal.ops.pay.OpsPointsWorkbenchController(
                    org.mockito.Mockito.mock(PointsWorkbenchAppService.class));
        }

        @Bean
        ai.neargo.shop.portal.ops.pay.OpsPointsTreasuryController treasuryController() {
            return new ai.neargo.shop.portal.ops.pay.OpsPointsTreasuryController(
                    org.mockito.Mockito.mock(PointsTreasuryAppService.class));
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
