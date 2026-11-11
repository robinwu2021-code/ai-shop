package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.neargo.shop.beauty.server.api.InternalWorkOrderEntitlementController;
import ai.neargo.shop.beauty.service.impl.WorkOrderEntitlementService;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderOriginPort.Origin;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseInternalApi;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Binding;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Lookup;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.State;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class WorkOrderEntitlementInternalTest {
    @Test
    void tokenAndExactHumanScopePrecedeReservationAndOriginalRecoveryCreatesNoHuman() {
        var owner = mock(WorkOrderEntitlementService.class);
        var controller = new InternalWorkOrderEntitlementController(owner, "wire");
        var lookup = new Lookup("MAIN", "E", "S", "OP");
        var binding = new Binding(lookup, "CLAIM", new Origin("WO", "TASK", 1, 0, "U", "SKU", 1),
                "a".repeat(64), State.RESERVED, null, null);
        var identity = new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A");
        var wrong = new InternalCall<>(new InternalRequestBinding(1, ServiceWorkOrderUseInternalApi.ENDPOINT,
                "RESERVE", identity, "OTHER", "S", "OP"), lookup);
        assertThat(controller.reserve(wrong, "wrong").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.reserve(wrong, "wire").getStatusCode().value()).isEqualTo(403);
        assertThat(controller.inspect(lookup, "wrong").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.reconcile(lookup, "wrong").getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(owner);
        var call = new InternalCall<>(new InternalRequestBinding(1, ServiceWorkOrderUseInternalApi.ENDPOINT,
                "RESERVE", identity, "E", "S", "OP"), lookup);
        when(owner.reserve(any(), any())).thenAnswer(invocation -> {
            ai.neargo.shop.spi.entitlement.EntitlementContext context = invocation.getArgument(0);
            assertThat(context.actorNo()).isEqualTo("A");
            assertThat(invocation.<Lookup>getArgument(1)).isEqualTo(lookup);
            return binding;
        });
        assertThat(controller.reserve(call, "wire").getStatusCode().value()).isEqualTo(200);
        when(owner.inspect(lookup)).thenReturn(binding);
        when(owner.reconcile(lookup)).thenReturn(binding);
        SecurityContextHolder.clearContext();
        assertThat(controller.inspect(lookup, "wire").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.reconcile(lookup, "wire").getStatusCode().value()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
