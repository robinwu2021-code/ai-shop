package ai.neargo.shop.commerce.api.internal;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.IdentityQuery;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.commerce.ChannelCatalogAuthorityInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A trusted service can read original channel authority facts without credentials or write side
 * effects.
 */
@RestController
public class InternalChannelCatalogAuthorityController {
    private final ChannelCatalogAuthorityService source;
    private final String token;

    public InternalChannelCatalogAuthorityController(
            ChannelCatalogAuthorityService source,
            @Value("${shop.services.internal-token:}") String token) {
        this.source = source;
        this.token = token;
    }

    @PostMapping(ChannelCatalogAuthorityInternalApi.RESOLVE_PATH)
    public ResponseEntity<?> resolve(
            @RequestBody(required = false) IdentityQuery request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            ChannelCatalogAuthorityPolicy.validateIdentityQuery(request);
            return source.resolve(request);
        });
    }

    @PostMapping(ChannelCatalogAuthorityInternalApi.CAPTURE_PATH)
    public ResponseEntity<?> capture(
            @RequestBody(required = false) Query request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(
                token,
                given,
                () -> {
                    ChannelCatalogAuthorityPolicy.validateQuery(request);
                    return source.capture(request);
                });
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(
            HttpMessageNotReadableException failure, HttpServletRequest request) {
        return InternalServiceResponses.invoke(
                token,
                request.getHeader(InternalHttp.TOKEN_HEADER),
                () -> {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                });
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> unavailable(RuntimeException failure, HttpServletRequest request) {
        return InternalServiceResponses.invoke(
                token,
                request.getHeader(InternalHttp.TOKEN_HEADER),
                () -> {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR);
                });
    }
}
