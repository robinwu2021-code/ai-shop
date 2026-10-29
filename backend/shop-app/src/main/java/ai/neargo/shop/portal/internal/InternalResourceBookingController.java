package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ResourceBookingInternalApi;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Owner-only machine bridge; public clients cannot reserve arbitrary resources or delegate an actor. */
@RestController
public class InternalResourceBookingController {
    private final ResourceBookingPort booking;
    private final ResourceCandidatesPort candidates;
    private final String token;
    public InternalResourceBookingController(ResourceBookingPort booking, ResourceCandidatesPort candidates,
            @Value("${shop.services.internal-token:}") String token) {
        this.booking = booking; this.candidates = candidates; this.token = token;
    }
    @PostMapping(ResourceBookingInternalApi.BASE + "accept")
    public ResponseEntity<?> accept(@RequestBody ResourceBookingInternalApi.Acceptance request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> actor(request.identity(), request.actor(), request.request().scope(),
                () -> booking.accept(request.actor(), request.request())));
    }
    @PostMapping(ResourceBookingInternalApi.BASE + "query")
    public ResponseEntity<?> query(@RequestBody ResourceBookingInternalApi.Command request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> actor(request.identity(), request.actor(), request.scope(), () -> lookup(
                booking.query(request.actor(), request.scope(), request.commandNo()).orElse(null))));
    }
    @PostMapping(ResourceBookingInternalApi.BASE + "execute")
    public ResponseEntity<?> execute(@RequestBody ResourceBookingInternalApi.Command request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> actor(request.identity(), request.actor(), request.scope(),
                () -> booking.execute(request.actor(), request.scope(), request.commandNo())));
    }
    @PostMapping(ResourceBookingInternalApi.BASE + "hold")
    public ResponseEntity<?> hold(@RequestBody ResourceBookingInternalApi.HoldQuery request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> {
            var b = request.binding();
            return actor(request.identity(), request.actor(),
                    new ResourceBookingPort.Scope(b.tenantNo(), b.entityNo(), b.storeNo()),
                    () -> new ResourceBookingInternalApi.BoundHold(b,
                            booking.queryHold(request.actor(), b, request.holdNo())));
        });
    }
    @PostMapping(ResourceBookingInternalApi.BASE + "reconcile")
    public ResponseEntity<?> reconcile(@RequestBody ResourceSourceAuthorityPort.Binding binding,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> lookup(booking.reconcile(binding).orElse(null)));
    }
    @PostMapping(ResourceBookingInternalApi.BASE + "reconcile-hold")
    public ResponseEntity<?> reconcileHold(@RequestBody ResourceBookingInternalApi.BoundHoldQuery request,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> new ResourceBookingInternalApi.BoundHold(request.binding(),
                booking.reconcileHold(request.binding(), request.holdNo())));
    }
    @PostMapping(ResourceBookingInternalApi.BASE + "candidates")
    public ResponseEntity<?> candidates(@RequestBody ResourceCandidatesPort.Query query,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> candidates.candidates(query));
    }
    @PostMapping(ResourceBookingInternalApi.BASE + "capacity")
    public ResponseEntity<?> capacity(@RequestBody ResourceCandidatesPort.CapacityQuery query,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> candidates.capacity(query));
    }
    private ResourceBookingInternalApi.Lookup lookup(ResourceBookingPort.Receipt receipt) {
        return new ResourceBookingInternalApi.Lookup(receipt != null, receipt);
    }
    private <T> T actor(DelegatedIdentity identity, ResourceBookingPort.Actor actor, ResourceBookingPort.Scope scope,
            Supplier<T> operation) {
        if (identity == null || actor == null || scope == null || !identity.tenantNo().equals(scope.tenantNo())
                || !identity.actorNo().equals(actor.operatorNo()) || !identity.realm().equals(actor.realm())
                || !identity.subjectKind().equals(actor.subjectKind())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        return DelegatedIdentityContext.call(identity, operation);
    }
    private ResponseEntity<?> invoke(String given, Supplier<?> operation) {
        try { return InternalServiceResponses.invoke(token, given, () -> {
            DelegatedIdentityContext.outside(); return operation.get();
        }); }
        catch (RuntimeException unavailable) { return ResponseEntity.status(503).build(); }
    }
    /** Internal decode failures use real status codes even when the host has public HTTP200 error advice. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> malformedRequest(HttpMessageNotReadableException failure) {
        return ResponseEntity.badRequest().build();
    }
}
