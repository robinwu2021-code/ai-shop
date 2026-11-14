package ai.neargo.shop.portal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Lookup;
import org.junit.jupiter.api.Test;

class WorkOrderEntitlementFactsIngressTest {
    @Test
    void invalidInternalTokenCannotInspectTheOriginalMemberOrAssetFacts() {
        var facts = mock(ServiceWorkOrderUseFactsPort.class);
        var controller = new InternalWorkOrderEntitlementFactsController(facts, "wire");
        var lookup = new Lookup("MAIN", "E", "S", "OP");
        assertThat(controller.inspect(lookup, "other").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.inspect(lookup, null).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(facts);
        controller.inspect(lookup, "wire");
        verify(facts).inspect(lookup);
    }
}
