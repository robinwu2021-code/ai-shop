package ai.neargo.shop.portal.internal;

import ai.neargo.shop.spi.promotion.ActivityParticipationPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationInternal.QueryRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;

class InternalActivityParticipationEndpointTest {
    @Test void missingConfigurationAndWrongTokenNeverInvokeBusiness() {
        var port=mock(ActivityParticipationPort.class);
        assertEquals(401,new InternalActivityParticipationEndpoint(port,"").query("anything",new QueryRequest(null,null)).getStatusCode().value());
        assertEquals(401,new InternalActivityParticipationEndpoint(port,"secret").query(null,new QueryRequest(null,null)).getStatusCode().value());
        assertEquals(401,new InternalActivityParticipationEndpoint(port,"secret").query("wrong",new QueryRequest(null,null)).getStatusCode().value());
        verifyNoInteractions(port);
    }
}
