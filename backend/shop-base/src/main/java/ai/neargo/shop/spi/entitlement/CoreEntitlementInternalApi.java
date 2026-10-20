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
    @PostExchange(EntitlementCoreInternal.LEGACY_COUPON_AUDIENCE)
    LegacyCouponAudiencePort.Fact legacyCouponAudience(@RequestBody EntitlementCoreInternal.LegacyAudience request);
    @PostExchange(EntitlementCoreInternal.HISTORY_RETURN_SOURCE)
    LegacyCouponHistoricalReturnSourcePort.Fact historicalReturnSource(@RequestBody EntitlementCoreInternal.HistoricalReturnQuery request);
    @PostExchange(EntitlementCoreInternal.HISTORY_RETURN_ACK)
    LegacyCouponHistoricalReturnPort.Receipt historicalReturnAck(@RequestBody EntitlementCoreInternal.HistoricalReturnAck request);
    @PostExchange(EntitlementCoreInternal.LEGACY_MIGRATION_COMMIT)
    EntitlementCoreInternal.MigrationCommitReply legacyMigrationCommit(@RequestBody EntitlementCoreInternal.MigrationCommitQuery request);
    @PostExchange(EntitlementCoreInternal.LEGACY_CHECKOUT_SOURCE)
    LegacyCouponCheckoutSourcePort.CheckoutFact legacyCheckoutSource(@RequestBody EntitlementCoreInternal.LegacyCheckoutQuery request);
    @PostExchange(EntitlementCoreInternal.LEGACY_CHECKOUT_DECISION)
    EntitlementCoreInternal.LegacyCheckoutDecision legacyCheckoutDecision(@RequestBody EntitlementCoreInternal.LegacyCheckoutQuery request);
    @PostExchange(EntitlementCoreInternal.LEGACY_CHECKOUT_REVERSAL)
    EntitlementCoreInternal.LegacyCheckoutReversal legacyCheckoutReversal(@RequestBody EntitlementCoreInternal.LegacyCheckoutQuery request);
    @PostExchange(EntitlementCoreInternal.FROZEN_LEGACY_MANIFEST)
    LegacyCouponFrozenSnapshotPort.Snapshot frozenLegacyManifest(@RequestBody EntitlementCoreInternal.FrozenSnapshotQuery request);
    @PostExchange(EntitlementCoreInternal.FROZEN_LEGACY_SNAPSHOT)
    LegacyCouponFrozenSnapshotPort.Snapshot frozenLegacySnapshot(@RequestBody EntitlementCoreInternal.FrozenSnapshotQuery request);
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

    @PostExchange(EntitlementCoreInternal.SALE_SOURCE)
    EntitlementSaleSourcePort.SaleSourceFact saleSource(@RequestBody EntitlementCoreInternal.SaleSourceQuery request);

    @PostExchange(EntitlementEventInternal.RECEIVE)
    EntitlementEventInternal.Accepted receiveEvent(@RequestBody EntitlementEventInternal.Envelope event);

}
