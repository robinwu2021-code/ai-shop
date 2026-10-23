package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreCardInternalApi;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import ai.neargo.shop.spi.user.StoreCardFacts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Original store-card authority serves trusted services; token validation precedes body and identity validation. */
@RestController
public class InternalStoreCardEndpoint {
    private final StoreCardQueryPort catalog;
    private final String token;
    private final Validator validator;

    public InternalStoreCardEndpoint(StoreCardQueryPort catalog,
            @Value("${shop.services.internal-token:}") String token, Validator validator) {
        this.catalog = catalog;
        this.token = token;
        this.validator = validator;
    }

    @PostMapping(StoreCardInternalApi.BATCH_PATH)
    public ResponseEntity<?> lookupBatch(@RequestBody(required=false) StoreCardInternalApi.BatchLookup request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER, required=false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            if (request == null || !validator.validate(request).isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
            StoreCardQueryPort.validateLookup(request.storeNos());
            var cards = catalog.cards(request.storeNos());
            StoreCardFacts.response(cards, request.storeNos());
            return request.storeNos().stream().filter(cards::containsKey)
                    .map(cards::get).map(StoreCardInternalApi.CardReceipt::from).toList();
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
