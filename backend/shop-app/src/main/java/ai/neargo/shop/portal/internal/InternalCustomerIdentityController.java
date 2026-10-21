package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Resolves actual member-owner scoped tickets; a result carries no consent or financial capability. */
@RestController
public class InternalCustomerIdentityController {
    private final CustomerIdentityPort customers;
    private final String token;
    public InternalCustomerIdentityController(CustomerIdentityPort customers,
            @Value("${shop.services.internal-token:}") String token) { this.customers = customers; this.token = token; }
    @PostMapping(CustomerIdentityInternalApi.PATH)
    public ResponseEntity<?> resolve(@RequestBody CustomerIdentityInternalApi.Request request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        try { return InternalServiceResponses.invoke(token, given, () -> {
            var i = request.identity(); var c = request.context();
            if (i == null || c == null || request.query() == null || !"MERCHANT".equals(i.realm())
                    || !i.tenantNo().equals(c.tenantNo()) || !i.actorNo().equals(c.actorNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return DelegatedIdentityContext.call(i, () -> new CustomerIdentityInternalApi.Resolution(i, c,
                    request.query(), customers.resolveReference(c, request.query())));
        }); } catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
    }
    /** Internal decode failures use real status codes even when the host has public HTTP200 error advice. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformedRequest(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().build();
    }
}
