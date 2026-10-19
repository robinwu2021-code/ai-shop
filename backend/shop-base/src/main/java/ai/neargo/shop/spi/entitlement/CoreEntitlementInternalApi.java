package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.SourceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.AuthorizationQuery;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.GrantSourceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.AudienceQuery;
import org.springframework.web.bind.annotation.RequestBody;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort.SourceFact;
import org.springframework.web.service.annotation.PostExchange;

/** Reverse facts and durable event handoff, shared by the real main-app server and pay-svc client. */
public interface CoreEntitlementInternalApi {
    @PostExchange(EntitlementCoreInternal.USE_SOURCE)
    EntitlementUseSourcePort.UseSourceFact useSource(@RequestBody SourceQuery request);
    @PostExchange(EntitlementCoreInternal.SOURCE_QUERY)
    SourceDecisionPort.SourceDecision decision(@RequestBody SourceQuery request);
    @PostExchange(EntitlementCoreInternal.AUTHORIZATION)
    SourceDecisionPort.AuthorizationDecision authorization(@RequestBody AuthorizationQuery request);
    @PostExchange(EntitlementCoreInternal.GRANT_SOURCE)
    EntitlementGrantSourcePort.SourceFact grantSource(@RequestBody GrantSourceQuery request);
    @PostExchange(EntitlementCoreInternal.GRANT_DECISION)
    SourceDecisionPort.SourceDecision grantDecision(@RequestBody SourceQuery request);
    @PostExchange(EntitlementCoreInternal.AUDIENCE)
    EntitlementIssueAudiencePort.AudienceSnapshot audience(@RequestBody AudienceQuery request);

    @PostExchange(EntitlementCoreInternal.RECHARGE_SOURCE)
    SourceFact rechargeSource(@RequestBody SourceQuery request);
    @PostExchange(EntitlementCoreInternal.RECHARGE_BY_NO)
    SourceFact rechargeByNo(
            @RequestBody EntitlementCoreInternal.RechargeQuery request);

    @PostExchange(EntitlementEventInternal.RECEIVE)
    EntitlementEventInternal.Accepted receiveEvent(@RequestBody EntitlementEventInternal.Envelope event);

}
