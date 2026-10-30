package ai.neargo.shop.retail.commerce.api.internal;

import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.retail.commerce.port.RetailCatalogSourcePortImpl;
import ai.neargo.shop.retail.commerce.port.RetailSourceValidationProviderImpl;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.CaptureCommand;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.RetailCatalogSourceInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Original internal token is checked before parsing; internal evidence never becomes a public input
 * surface.
 */
@RestController
public final class InternalRetailCatalogSourceController {
    private final String token;
    private final RetailSourceValidationProviderImpl validation;
    private final ObjectProvider<RetailCatalogSourcePortImpl> catalog;
    private final ObjectMapper json;
    private final Clock clock;

    public InternalRetailCatalogSourceController(
            String token,
            RetailSourceValidationProviderImpl validation,
            ObjectProvider<RetailCatalogSourcePortImpl> catalog,
            ObjectMapper json,
            Clock clock) {
        this.token = token;
        this.validation = validation;
        this.catalog = catalog;
        this.json = json;
        this.clock = clock;
    }

    @PostMapping(RetailCatalogSourceInternalApi.CURRENT_PATH)
    public ResponseEntity<?> current(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given,
            @RequestBody(required = false) byte[] body) {
        return InternalServiceResponses.invoke(
                token, given, () -> validation.current(read(body, Source.class)));
    }

    @PostMapping(RetailCatalogSourceInternalApi.VALIDATE_PATH)
    public ResponseEntity<?> validate(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given,
            @RequestBody(required = false) byte[] body) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    var source = read(body, Source.class);
                    var proof = validation.validate(source);
                    CatalogSourceTransportPolicy.validateProof(source, proof, clock);
                    return proof;
                });
    }

    @PostMapping(RetailCatalogSourceInternalApi.CAPTURE_PATH)
    public ResponseEntity<?> capture(
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given,
            @RequestBody(required = false) byte[] body) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    var source = catalog.getIfAvailable();
                    if (source == null) throw BizException.of(ErrorCode.NOT_FOUND);
                    return source.capture(read(body, CaptureCommand.class));
                });
    }

    private <T> T read(byte[] body, Class<T> type) {
        try {
            return CatalogSourceTransportPolicy.read(json, body, type);
        } catch (RuntimeException malformed) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
