package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSaleInternalApi;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;
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

@RestController
@ConditionalOnProperty(name = "shop.trade.channel.enabled", havingValue = "true")
public final class InternalChannelAfterSaleSourceController {
    private final IndustryChannelAfterSalePort owner;
    private final ObjectMapper json;
    private final String token;

    public InternalChannelAfterSaleSourceController(
            IndustryChannelAfterSalePort owner,
            ObjectMapper json,
            @Value("${shop.services.internal-token:}") String token) {
        this.owner = owner;
        this.json = json;
        this.token = token;
    }

    @PostMapping(IndustryChannelAfterSaleInternalApi.READ_PATH)
    public ResponseEntity<?> read(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given,
            @RequestBody(required = false) byte[] body) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    if (body == null || body.length == 0 || body.length > 65536)
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    IndustryChannelAfterSalePort.Query query;
                    try {
                        query =
                                json.readerFor(IndustryChannelAfterSalePort.Query.class)
                                        .with(
                                                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                        .readValue(body);
                    } catch (RuntimeException bad) {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                    return owner.get(query);
                });
    }
}
