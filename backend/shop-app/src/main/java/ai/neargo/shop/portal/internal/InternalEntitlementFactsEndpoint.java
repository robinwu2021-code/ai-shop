package ai.neargo.shop.portal.internal;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.paybridge.EntitlementEventReceiverService;
import ai.neargo.shop.spi.entitlement.EntitlementEventInternal;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.SourceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.AuthorizationQuery;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.GrantSourceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.AudienceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementGrantSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Core remains the sole owner of business source, consent and audience truth in both deployment modes. */
@RestController
public class InternalEntitlementFactsEndpoint {
    private final ObjectProvider<EntitlementUseSourcePort> uses;
    private final SourceDecisionPort decisions;
    private final ObjectProvider<EntitlementIssueAudiencePort> audiences;
    private final List<EntitlementGrantSourcePort> grantSources;
    private final String token;
    private final EntitlementEventReceiverService events;
    public InternalEntitlementFactsEndpoint(ObjectProvider<EntitlementUseSourcePort> uses, SourceDecisionPort decisions,
            ObjectProvider<EntitlementIssueAudiencePort> audiences, List<EntitlementGrantSourcePort> grantSources,
            EntitlementEventReceiverService events, @Value("${shop.services.internal-token:}") String token) {
        this.uses = uses; this.decisions = decisions; this.audiences = audiences;
        this.grantSources = grantSources; this.token = token; this.events = events;
    }
    @PostMapping(EntitlementCoreInternal.USE_SOURCE)
    public ResponseEntity<?> useSource(@RequestBody SourceQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            core(r.source()); var owner = uses.getIfAvailable();
            if (owner == null) unavailable();
            return owner.resolve(r.context(), r.source());
        });
    }
    @PostMapping(EntitlementCoreInternal.SOURCE_QUERY)
    public ResponseEntity<?> decision(@RequestBody SourceQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            core(r.source()); return decisions.query(r.context(), r.source());
        });
    }
    @PostMapping(EntitlementCoreInternal.AUTHORIZATION)
    public ResponseEntity<?> authorization(@RequestBody AuthorizationQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given,
                () -> decisions.authorization(r.context(), r.authorizationRef()));
    }
    @PostMapping(EntitlementCoreInternal.GRANT_SOURCE)
    public ResponseEntity<?> grantSource(@RequestBody GrantSourceQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            core(r.command().sourceRef());
            var owners = grantSources.stream().filter(p -> p.supports(r.command().sourceRef())).toList();
            if (owners.size() != 1) unavailable();
            return owners.getFirst().resolve(r.context(), r.command());
        });
    }
    @PostMapping(EntitlementCoreInternal.GRANT_DECISION)
    public ResponseEntity<?> grantDecision(@RequestBody SourceQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            core(r.source());
            var owners = grantSources.stream().filter(p -> p.supports(r.source())).toList();
            if (owners.size() > 1) unavailable();
            var fact = owners.isEmpty() ? null : owners.getFirst().decision(r.context(), r.source());
            return fact == null ? decisions.query(r.context(), r.source()) : fact;
        });
    }
    @PostMapping(EntitlementCoreInternal.AUDIENCE)
    public ResponseEntity<?> audience(@RequestBody AudienceQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> {
            var owner = audiences.getIfAvailable(); if (owner == null) unavailable();
            return owner.resolve(r.context(), r.entityNo(), r.audienceFactRef());
        });
    }
    @PostMapping(EntitlementEventInternal.RECEIVE)
    public ResponseEntity<?> receiveEvent(@RequestBody EntitlementEventInternal.Envelope r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> events.receive(r));
    }
    private static void core(SourceRef source) {
        if (!"CORE".equals(source.sourceSystem())) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static void unavailable() { throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
}
