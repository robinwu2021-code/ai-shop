package ai.neargo.shop.portal.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Original billing facts select the exact permission even before a preview exists. */
class WorkOrderEntitlementPermissionTest {
    private final EntitlementServiceOrderService orders = mock(EntitlementServiceOrderService.class);
    private final EntitlementServiceUseCoordinator uses = mock(EntitlementServiceUseCoordinator.class);
    private final BizEntitlementUseController controller = new BizEntitlementUseController(
            orders, uses, null, null, null, null);

    private void original(long amount) {
        var order = mock(ServiceOrderSnapshot.class);
        var fact = mock(UseSourceFact.class);
        when(order.fact()).thenReturn(fact);
        when(fact.storeNo()).thenReturn("S");
        when(fact.items()).thenReturn(List.of(new UseItem("SL1", "G", "Care", 1, amount)));
        when(order.status()).thenReturn("COMMITTED");
        when(order.serviceOrderNo()).thenReturn("SO");
        when(orders.findOperation("MAIN", "E", "OP")).thenReturn(order);
        when(uses.query(any(), eq("E"), eq("OP"))).thenReturn(new HoldView("OP", State.PROCESSING, null, null, null));
        when(uses.cancel(any(), eq("E"), eq("S"), eq("SO"), eq("Cancel")))
                .thenReturn(new HoldView("OP", State.RELEASED, null, null, null));
    }

    private void grants(String store, String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("A", "Operator"), null, List.of()));
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of(store), store, false,
                Map.of(), Map.of(store, Set.of(permissions))));
    }

    @AfterEach void clear() { BizContext.clear(); SecurityContextHolder.clearContext(); }

    @Test void storedValueOriginalUsesItsOwnPermissionWithoutAQuote() {
        original(100);
        grants("S", BizPerms.SV_CONSUME_CREATE);
        assertThat(controller.query("OP").status()).isEqualTo("PROCESSING");
        assertThat(controller.cancel("OP", new BizEntitlementUseController.CancelRequest("Cancel")).status())
                .isEqualTo("RELEASED");
        grants("S", BizPerms.ENT_READ, BizPerms.ENT_USE);
        assertThatThrownBy(() -> controller.query("OP")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> controller.cancel("OP", new BizEntitlementUseController.CancelRequest("Cancel")))
                .isInstanceOf(BizException.class);
    }

    @Test void storedValuePermissionCannotReadOrCancelAUnitsOriginal() {
        original(0);
        grants("S", BizPerms.SV_CONSUME_CREATE);
        assertThatThrownBy(() -> controller.query("OP")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> controller.cancel("OP", new BizEntitlementUseController.CancelRequest("Cancel")))
                .isInstanceOf(BizException.class);
        grants("S", BizPerms.ENT_READ, BizPerms.ENT_USE);
        assertThat(controller.query("OP").status()).isEqualTo("PROCESSING");
        assertThat(controller.cancel("OP", new BizEntitlementUseController.CancelRequest("Cancel")).status())
                .isEqualTo("RELEASED");
    }

    @Test void assetPermissionDoesNotGrantAnotherStore() {
        original(100);
        grants("OTHER", BizPerms.SV_CONSUME_CREATE);
        assertThatThrownBy(() -> controller.query("OP")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> controller.cancel("OP", new BizEntitlementUseController.CancelRequest("Cancel")))
                .isInstanceOf(BizException.class);
    }
}
