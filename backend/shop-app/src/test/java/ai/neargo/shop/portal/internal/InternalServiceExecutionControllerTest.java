package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionInternalApi;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.Grant;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.PrepareExecution;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.QuantityFacts;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Transport-only boundaries; these doubles do not stand in for real quantity owner scenario coverage. */
class InternalServiceExecutionControllerTest {
    private static final String HASH = "a".repeat(64);
    private final ServiceExecutionPort owner = mock(ServiceExecutionPort.class);
    private final InternalServiceExecutionController controller = new InternalServiceExecutionController(owner, "secret");

    @Test
    void invalidTokenAndWrongHumanRouteDoNotReachQuantityOwner() {
        var b = binding();
        var request = new PrepareExecution(b, "action", HASH, HASH);
        var call = new InternalCall<>(new InternalRequestBinding(1, ServiceExecutionInternalApi.ENDPOINT, "QUERY",
                new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"), "entity", "store", "execution"), request);
        assertEquals(401, controller.prepare(call, "wrong").getStatusCode().value());
        assertEquals(400, controller.prepare(call, "secret").getStatusCode().value());
        verifyNoInteractions(owner);
    }

    @Test
    void originalQueryNeverInstallsTheOriginalEmployee() {
        var before = SecurityContextHolder.getContext();
        var authentication = before.getAuthentication();
        var b = binding();
        var lookup = new ExecutionLookup(b);
        var call = new ExecutionOriginalProtocol.Call<>(new ExecutionOriginalProtocol.Binding(1,
                ServiceExecutionInternalApi.ENDPOINT, "QUERY_ORIGINAL", b.scope(), b.executionNo(),
                ServiceExecutionContract.bindingHash(b)), lookup);
        when(owner.queryOriginal(any())).thenAnswer(invocation -> {
            assertSame(before, SecurityContextHolder.getContext());
            assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
            return new Grant(b, "grant", 1, GrantState.PREPARED, 0, new QuantityFacts(1, 1, 0, 0), null);
        });
        assertEquals(200, controller.queryOriginal(call, "secret").getStatusCode().value());
        assertSame(before, SecurityContextHolder.getContext());
        assertSame(authentication, SecurityContextHolder.getContext().getAuthentication());
    }

    private static ExecutionBinding binding() {
        return new ExecutionBinding(new ExecutionScope("MAIN", "entity", "store"), "BEAUTY", "operation",
                "order", "child", "line", 1, "work", 1, "task", 0, "binding", 1, "execution", 1,
                "submission", "fence", 1, 1, 1, HASH, "definition", HASH, HASH, HASH);
    }
}
