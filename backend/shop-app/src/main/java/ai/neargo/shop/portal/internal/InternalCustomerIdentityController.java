package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.auth.BoundDelegatedCalls.Mode;
import ai.neargo.shop.spi.security.InternalCall;
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
    private final BoundDelegatedCalls bound;

    public InternalCustomerIdentityController(
            CustomerIdentityPort customers,
            @Value("${shop.services.internal-token:}") String token) {
        this.customers = customers;
        this.token = token;
        this.bound = new BoundDelegatedCalls(token);
    }

    /** Canonical member selection is a separately bound purpose, not a ticket or consumer proof. */
    @PostMapping(CustomerIdentityInternalApi.COUNTER_REFERENCE_PATH)
    public ResponseEntity<?> resolveCounterReference(
            @RequestBody InternalCall<CustomerIdentityInternalApi.CounterReferenceRequest> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        String target =
                call == null || call.request().query() == null
                        ? null
                        : call.request().query().memberNo();
        return bound.invoke(
                given,
                call,
                CustomerIdentityInternalApi.COUNTER_ENDPOINT,
                "RESOLVE",
                Mode.MERCHANT,
                target,
                () -> {
                    var request = call.request();
                    var identity = request.identity();
                    var context = request.context();
                    var query = request.query();
                    if (identity == null
                            || context == null
                            || query == null
                            || !identity.equals(call.binding().identity())
                            || !context.tenantNo().equals(identity.tenantNo())
                            || !context.actorNo().equals(identity.actorNo())
                            || !query.entityNo().equals(call.binding().entityNo())
                            || !query.storeNo().equals(call.binding().storeNo())) {
                        throw BizException.of(ErrorCode.FORBIDDEN);
                    }
                    return new CustomerIdentityInternalApi.CounterReferenceResolution(
                            request, customers.resolveCounterReference(context, query));
                });
    }

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
    @PostMapping(CustomerIdentityInternalApi.CLAIM_REFERENCE_PATH)
    public ResponseEntity<?> resolveClaimReference(@RequestBody CustomerIdentityInternalApi.ClaimReferenceRequest req,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        try { return InternalServiceResponses.invoke(token, given, () -> {
            if (req == null || req.identity() == null || req.context() == null || req.query() == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            var identity = req.identity(); var context = req.context();
            if (!"MERCHANT".equals(identity.realm()) || !identity.tenantNo().equals(context.tenantNo())
                    || !identity.actorNo().equals(context.actorNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
            return DelegatedIdentityContext.call(identity, () -> new CustomerIdentityInternalApi.ClaimReferenceResolution(
                    req, customers.resolveClaimReference(context, req.query())));
        }); } catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
    }
    @PostMapping(CustomerIdentityInternalApi.PROOF_PATH)
    public ResponseEntity<?> verifyConsumerProof(@RequestBody CustomerIdentityInternalApi.ProofRequest request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        try { return InternalServiceResponses.invoke(token, given, () -> {
            if (request == null || request.identity() == null || request.context() == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            var i = request.identity(); var c = request.context();
            if (!"CONSUMER".equals(i.realm()) || !"USR".equals(i.subjectKind())
                    || !i.tenantNo().equals(c.tenantNo()) || !i.actorNo().equals(c.actorNo())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return DelegatedIdentityContext.call(i, () -> new CustomerIdentityInternalApi.ProofResolution(request,
                    customers.verifyConsumerProof(c, request.memberCode())));
        }); } catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
    }
    /** Internal decode failures use real status codes even when the host has public HTTP200 error advice. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformedRequest(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().build();
    }
}
