package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
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

/** The original product owner serves metadata to trusted services; no user authentication is replaced. */
@RestController
public class InternalGoodsCatalogEndpoint {
    private final GoodsCatalogQueryPort catalog;
    private final String token;
    private final Validator validator;

    public InternalGoodsCatalogEndpoint(GoodsCatalogQueryPort catalog,
            @Value("${shop.services.internal-token:}") String token, Validator validator) {
        this.catalog = catalog;
        this.token = token;
        this.validator = validator;
    }

    @PostMapping(GoodsCatalogInternalApi.LOOKUP_PATH)
    public ResponseEntity<?> lookup(@RequestBody(required = false) GoodsCatalogInternalApi.Lookup request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            if (request == null || !validator.validate(request).isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
            return catalog.getGoods(request.entityNo(), request.storeNo(), request.goodsNo());
        });
    }
    @PostMapping(GoodsCatalogInternalApi.BATCH_PATH)
    public ResponseEntity<?> lookupBatch(@RequestBody(required=false) GoodsCatalogInternalApi.BatchLookup request,
            @RequestHeader(value=InternalHttp.TOKEN_HEADER, required=false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            if (request == null || !validator.validate(request).isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
            return catalog.getGoodsBatch(request.entityNo(), request.storeNo(), request.goodsNos());
        });
    }

    /** Public global advice uses HTTP 200 envelopes; internal malformed requests retain token/status semantics. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformedBody(HttpMessageNotReadableException failure, HttpServletRequest request) {
        return InternalServiceResponses.invoke(token, request.getHeader(InternalHttp.TOKEN_HEADER), () -> {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        });
    }
}
