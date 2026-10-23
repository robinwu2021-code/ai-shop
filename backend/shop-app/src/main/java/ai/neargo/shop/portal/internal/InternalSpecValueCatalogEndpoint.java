package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.SpecValueCatalogInternalApi;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Original spec authority serves trusted services; token validation precedes body and identity validation. */
@RestController
public class InternalSpecValueCatalogEndpoint {
    private final SpecValueCatalogQueryPort catalog;
    private final String token;
    private final Validator validator;

    public InternalSpecValueCatalogEndpoint(SpecValueCatalogQueryPort catalog,
            @Value("${shop.services.internal-token:}") String token, Validator validator) {
        this.catalog = catalog;
        this.token = token;
        this.validator = validator;
    }

    @PostMapping(SpecValueCatalogInternalApi.BATCH_PATH)
    public ResponseEntity<?> lookupBatch(@RequestBody(required=false) SpecValueCatalogInternalApi.BatchLookup request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER, required=false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            if (request == null || !validator.validate(request).isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
            SpecValueCatalogQueryPort.validateLookup(request.entityNo(), request.valueNos());
            return catalog.getValues(request.entityNo(), request.valueNos());
        });
    }

    /** Public global advice uses HTTP 200 envelopes; internal malformed requests retain token/status semantics. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedBody(HttpMessageNotReadableException failure, HttpServletRequest request) {
        return InternalServiceResponses.invoke(token, request.getHeader(InternalHttp.TOKEN_HEADER), () -> {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        });
    }
    /** Keeps authority outages out of the public HTTP-200 advice and never exposes database details. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> unavailable(RuntimeException failure, HttpServletRequest request) {
        return InternalServiceResponses.invoke(token, request.getHeader(InternalHttp.TOKEN_HEADER), () -> {
            throw BizException.of(ErrorCode.INTERNAL_ERROR);
        });
    }

}
