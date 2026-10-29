package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.personnel.PersonnelOperatorInternalApi;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine authentication precedes route binding, current identity and the owner's live permission
 * query.
 */
@RestController
public class InternalPersonnelOperatorController {
    private final PersonnelOperatorFactsPort personnel;
    private final BoundDelegatedCalls calls;
    private final InternalBindings bindings;

    public InternalPersonnelOperatorController(
            PersonnelOperatorFactsPort personnel,
            @Value("${shop.services.internal-token:}") String token) {
        this.personnel = personnel;
        this.calls = new BoundDelegatedCalls(token);
        this.bindings = new InternalBindings(token);
    }

    @PostMapping(PersonnelOperatorInternalApi.PATH)
    public ResponseEntity<?> current(
            @RequestBody InternalCall<PersonnelOperatorFactsPort.Query> request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return calls.invoke(
                given,
                request,
                PersonnelOperatorInternalApi.ENDPOINT,
                "CURRENT",
                BoundDelegatedCalls.Mode.MERCHANT,
                request == null ? null : request.request().access().operatorNo(),
                () -> {
                    var q = request.request().access();
                    bindings.requireCoordinates(
                            request, q.tenantNo(), q.entityNo(), q.storeNo(), q.operatorNo());
                    var identity = DelegatedIdentityContext.current();
                    if (!identity.realm().equals(q.realm())
                            || !identity.subjectKind().equals(q.subjectKind())) {
                        throw BizException.of(ErrorCode.FORBIDDEN);
                    }
                    return personnel.current(request.request());
                });
    }

    /** Internal decode errors retain their actual status even under the public HTTP200 advice. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformedRequest(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().build();
    }
}
