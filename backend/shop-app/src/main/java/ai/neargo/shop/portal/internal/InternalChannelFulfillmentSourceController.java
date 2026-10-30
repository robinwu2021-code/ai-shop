package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourceInternalApi;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourcePort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Service token is verified before an exact scoped original immutable receipt can be read. */
@RestController
@ConditionalOnProperty(name = "shop.trade.channel.enabled", havingValue = "true")
public final class InternalChannelFulfillmentSourceController {
    private final IndustryChannelFulfillmentSourcePort source;
    private final ObjectMapper json;
    private final String token;

    public InternalChannelFulfillmentSourceController(
            IndustryChannelFulfillmentSourcePort source,
            ObjectMapper json,
            @Value("${shop.services.internal-token:}") String token) {
        this.source = source;
        this.json = json;
        this.token = token;
    }

    @PostMapping(IndustryChannelFulfillmentSourceInternalApi.READ_PATH)
    public ResponseEntity<?> read(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given,
            @RequestBody(required = false) byte[] body) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    if (body == null || body.length == 0 || body.length > 65536)
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    IndustryChannelFulfillmentSourcePort.Query query;
                    try {
                        query =
                                json.readerFor(IndustryChannelFulfillmentSourcePort.Query.class)
                                        .with(
                                                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                        .readValue(body);
                    } catch (RuntimeException invalid) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    return source.read(query);
                });
    }
}
