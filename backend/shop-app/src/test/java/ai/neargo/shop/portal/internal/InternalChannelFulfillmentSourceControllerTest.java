package ai.neargo.shop.portal.internal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

class InternalChannelFulfillmentSourceControllerTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void originalServiceTokenIsCheckedBeforeMalformedPrivateQueryIsParsed() {
        var owner = mock(IndustryChannelFulfillmentSourcePort.class);
        var endpoint = new InternalChannelFulfillmentSourceController(owner, json, "secret");
        assertThat(
                        endpoint.read("wrong", "not-json".getBytes(StandardCharsets.UTF_8))
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        assertThat(
                        new InternalChannelFulfillmentSourceController(owner, json, "")
                                .read("secret", null)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        verifyNoInteractions(owner);
    }

    @Test
    void exactQueryIsForwardedButUnknownOrCoercedFieldsNeverReachOriginalOwner() {
        var owner = mock(IndustryChannelFulfillmentSourcePort.class);
        var endpoint = new InternalChannelFulfillmentSourceController(owner, json, "secret");
        var query =
                new IndustryChannelFulfillmentSourcePort.Query(
                        new Scope("MAIN", "E", "S"), "SOURCE", "ORDER", "F", "1");
        when(owner.read(query)).thenReturn(null);
        assertThat(endpoint.read("secret", json.writeValueAsBytes(query)).getStatusCode().value())
                .isEqualTo(503);
        verify(owner).read(query);
        var untouched = mock(IndustryChannelFulfillmentSourcePort.class);
        var strict = new InternalChannelFulfillmentSourceController(untouched, json, "secret");
        for (String body :
                List.of(
                        "{\"scope\":{\"tenantNo\":\"MAIN\",\"entityNo\":\"E\",\"storeNo\":\"S\"},\"revision\":1}",
                        "{\"forceSuccess\":true}"))
            assertThat(
                            strict.read("secret", body.getBytes(StandardCharsets.UTF_8))
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(400);
        verifyNoInteractions(untouched);
    }
}
