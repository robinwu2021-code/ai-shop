package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.pay.points.admin.PointsWorkbenchService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.query.PointsOverviewService;
import ai.neargo.shop.payclient.impl.PointsOpsAppServiceImpl;
import ai.neargo.shop.payclient.impl.PointsWorkbenchAppServiceImpl;
import ai.neargo.shop.payclient.impl.PointsInspectionAppServiceImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

class PointsOpsDataScopeTest {
    @Test
    void restricted_operator_can_read_and_modify_own_store_but_not_other_store() {
        restricted();
        var stores = mock(ai.neargo.shop.spi.user.StoreDirectoryPort.class);
        when(stores.cards(List.of("STORE_A")))
                .thenReturn(
                        java.util.Map.of(
                                "STORE_A",
                                new ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard(
                                        "STORE_A",
                                        "QA",
                                        "MERCHANT_A",
                                        null,
                                        "ACTIVE",
                                        true,
                                        null,
                                        null,
                                        null,
                                        null,
                                        0,
                                        0)));
        when(stores.cards(List.of("STORE_B")))
                .thenReturn(
                        java.util.Map.of(
                                "STORE_B",
                                new ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard(
                                        "STORE_B",
                                        "QA",
                                        "MERCHANT_B",
                                        null,
                                        "ACTIVE",
                                        true,
                                        null,
                                        null,
                                        null,
                                        null,
                                        0,
                                        0)));
        var kernel = mock(PointsWorkbenchService.class);
        var app = new PointsWorkbenchAppServiceImpl(kernel);
        org.springframework.test.util.ReflectionTestUtils.setField(app, "stores", stores);
        app.statements("T", "STORE_A", 0, 10);
        var request =
                new PointsWorkbenchService.StatementChange(
                        "I",
                        "T",
                        "STORE_A",
                        "S",
                        1,
                        PointsWorkbenchService.StatementAction.CONFIRM,
                        "QA");
        app.changeStatement(request, "OPS");
        assertThatThrownBy(() -> app.statements("T", "STORE_B", 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> app.statements("T", "UNKNOWN", 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        verify(kernel).statements("T", "STORE_A", 0, 10);
        verify(kernel).changeStatement(request, "OPS");
        verifyNoMoreInteractions(kernel);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void restricted() {
        var user =
                LoginUser.operator(
                        "OPS",
                        "QA",
                        List.of(),
                        List.of("finance:settle:read", "finance:settle:execute"),
                        DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("MERCHANT_A")));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Test
    void restricted_operator_cannot_read_global_pool_or_other_store() {
        restricted();
        var kernel = mock(PointsOverviewService.class);
        var app = new PointsOpsAppServiceImpl(kernel);
        assertThatThrownBy(() -> app.overview("T", "CN")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> app.receivables("T", "STORE_B", 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(kernel);
    }

    @Test
    void restricted_operator_cannot_modify_other_store() {
        restricted();
        var kernel = mock(PointsWorkbenchService.class);
        var app = new PointsWorkbenchAppServiceImpl(kernel);
        var request =
                new PointsWorkbenchService.StatementChange(
                        "I",
                        "T",
                        "STORE_B",
                        "S",
                        1,
                        PointsWorkbenchService.StatementAction.CONFIRM,
                        "QA");
        assertThatThrownBy(() -> app.changeStatement(request, "OPS"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(kernel);
    }

    @Test
    void restricted_operator_can_only_page_one_authorized_store_account() {
        restricted();
        var stores = mock(ai.neargo.shop.spi.user.StoreDirectoryPort.class);
        when(stores.cards(List.of("STORE_A"))).thenReturn(java.util.Map.of("STORE_A",
                new ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard("STORE_A", "QA", "MERCHANT_A", null, "ACTIVE", true, null, null, null, null, 0, 0)));
        when(stores.cards(List.of("STORE_B"))).thenReturn(java.util.Map.of("STORE_B",
                new ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard("STORE_B", "QA", "MERCHANT_B", null, "ACTIVE", true, null, null, null, null, 0, 0)));
        var kernel = mock(PointsInspectionService.class);
        var app = new PointsInspectionAppServiceImpl(kernel);
        org.springframework.test.util.ReflectionTestUtils.setField(app, "stores", stores);

        app.accounts("T", ai.neargo.shop.pay.points.model.HolderType.STORE, "STORE_A", 0, 10);
        app.transactions("T", ai.neargo.shop.pay.points.admin.PointsInspectionService.TransactionDirection.EARN,
                java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 20), "STORE_A", null, 0, 10);
        assertThatThrownBy(() -> app.accounts("T", ai.neargo.shop.pay.points.model.HolderType.STORE, null, 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> app.accounts("T", ai.neargo.shop.pay.points.model.HolderType.USER, null, 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> app.transactions("T", ai.neargo.shop.pay.points.admin.PointsInspectionService.TransactionDirection.EARN,
                java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 20), null, "USER_A", 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> app.accounts("T", ai.neargo.shop.pay.points.model.HolderType.STORE, "STORE_B", 0, 10))
                .isInstanceOf(AccessDeniedException.class);
        verify(kernel).accounts("T", ai.neargo.shop.pay.points.model.HolderType.STORE, "STORE_A", 0, 10);
        verify(kernel).transactions("T", ai.neargo.shop.pay.points.admin.PointsInspectionService.TransactionDirection.EARN,
                java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 20), "STORE_A", null, 0, 10);
        verifyNoMoreInteractions(kernel);
    }
}
