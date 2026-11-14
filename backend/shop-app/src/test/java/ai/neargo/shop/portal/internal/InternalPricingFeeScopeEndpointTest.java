package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.trade.PricingFeeScopeInternalApi.OriginalQuery;
import ai.neargo.shop.spi.trade.PricingFeeScopeInternalApi.Query;
import ai.neargo.shop.spi.trade.PricingFeeScopePort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** C8: fee-scope reads expose order/fee facts of any entity+store, so they need the shared internal token. */
class InternalPricingFeeScopeEndpointTest {
    private static final Scope SCOPE = new Scope("MAIN", "E1", "S1");

    @Test void missingConfigurationMissingOrWrongTokenNeverReachPort() {
        var port = mock(PricingFeeScopePort.class);
        var q = new Query(SCOPE, "FS1");
        var o = new OriginalQuery(SCOPE, "APP1");
        assertEquals(401, new InternalPricingFeeScopeEndpoint(port, "").pending(q, "anything").getStatusCode().value());
        assertEquals(401, new InternalPricingFeeScopeEndpoint(port, "secret").pending(q, null).getStatusCode().value());
        assertEquals(401, new InternalPricingFeeScopeEndpoint(port, "secret").pending(q, "wrong").getStatusCode().value());
        assertEquals(401, new InternalPricingFeeScopeEndpoint(port, "secret").original(o, null).getStatusCode().value());
        assertEquals(401, new InternalPricingFeeScopeEndpoint(port, "secret").original(o, "wrong").getStatusCode().value());
        verifyNoInteractions(port);
    }

    @Test void rightTokenReturnsPortResultAsPlainBody() {
        var port = mock(PricingFeeScopePort.class);
        var row = new PricingFeeScopePort.AdjustmentAuthority("APP1", "O1", "SO1", "CNY", 100, 80, "h");
        when(port.pendingAdjustments(SCOPE, "FS1")).thenReturn(List.of(row));
        var res = new InternalPricingFeeScopeEndpoint(port, "secret").pending(new Query(SCOPE, "FS1"), "secret");
        assertEquals(200, res.getStatusCode().value());
        assertEquals(List.of(row), res.getBody());
    }
}
