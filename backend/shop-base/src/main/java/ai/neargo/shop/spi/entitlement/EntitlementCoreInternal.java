package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import java.util.Objects;

/** Core fact lookup protocol. Only service-authenticated main-app endpoints may supply these responses. */
public final class EntitlementCoreInternal {
    private EntitlementCoreInternal() { }
    public static final String LEGACY_COUPON_AUDIENCE="/internal/core/entitlement/legacy-coupon/audience";
    public record LegacyAudience(EntitlementContext context,LegacyCouponAudiencePort.Query query) {
        public LegacyAudience {ContractChecks.present(context);ContractChecks.present(query);}
    }
    public static final String HISTORY_RETURN_SOURCE="/internal/core/entitlement/migration/history-return/source";
    public static final String HISTORY_RETURN_ACK="/internal/core/entitlement/migration/history-return/ack";
    public record HistoricalReturnQuery(EntitlementContext context,String returnNo) {
        public HistoricalReturnQuery {ContractChecks.present(context);ContractChecks.id(returnNo);}
    }
    public record HistoricalReturnAck(EntitlementContext context,LegacyCouponHistoricalReturnPort.Receipt receipt) {
        public HistoricalReturnAck {ContractChecks.present(context);ContractChecks.present(receipt);}
    }
    public static final String LEGACY_MIGRATION_COMMIT="/internal/core/entitlement/migration/source-commit";
    public record MigrationCommitQuery(EntitlementContext context,EntitlementOwnerRegistryPort.SourceIdentity template) {
        public MigrationCommitQuery { ContractChecks.present(context);ContractChecks.present(template); }
    }
    /** A present envelope with null proof is undecided; absent transport is unavailable. */
    public record MigrationCommitReply(LegacyCouponSourceCommitPort.CommitProof proof) { }
    public static final String FROZEN_LEGACY_MANIFEST="/internal/core/entitlement/migration/frozen-manifest";
    public static final String FROZEN_LEGACY_SNAPSHOT="/internal/core/entitlement/migration/frozen-snapshot";
    public record FrozenSnapshotQuery(EntitlementContext context,LegacyCouponFrozenSnapshotPort.Query query) {
        public FrozenSnapshotQuery { ContractChecks.present(context); ContractChecks.present(query); }
    }
    public static final String LEGACY_CHECKOUT_SOURCE="/internal/core/entitlement/legacy-checkout/source";
    public static final String LEGACY_CHECKOUT_DECISION="/internal/core/entitlement/legacy-checkout/decision";
    public static final String LEGACY_CHECKOUT_REVERSAL="/internal/core/entitlement/legacy-checkout/reversal";
    public record LegacyCheckoutQuery(EntitlementContext context,String checkoutNo) {
        public LegacyCheckoutQuery { ContractChecks.present(context); ContractChecks.id(checkoutNo); }
    }
    /** A present envelope distinguishes an undecided source from an unavailable source service. */
    public record LegacyCheckoutDecision(LegacyCouponCheckoutSourcePort.Decision decision) { }
    public record LegacyCheckoutReversal(LegacyCouponCheckoutSourcePort.Reversal reversal) { }
    public static final String USE_SOURCE = "/internal/core/entitlement/source/use";
    public static final String SOURCE_QUERY = "/internal/core/entitlement/source/query";
    public static final String AUTHORIZATION = "/internal/core/entitlement/authorization/query";
    public static final String GRANT_SOURCE = "/internal/core/entitlement/source/grant";
    public static final String GRANT_DECISION = "/internal/core/entitlement/source/grant-decision";
    public static final String RECHARGE_SOURCE = "/internal/core/stored-value/source";
    public static final String RECHARGE_BY_NO = "/internal/core/stored-value/source-by-recharge";
    public record RechargeQuery(EntitlementContext context, String rechargeNo) {
        public RechargeQuery { Objects.requireNonNull(context); ContractChecks.id(rechargeNo); }
    }
    public static final String SALE_SOURCE = "/internal/core/entitlement/source/sale";
    public record SaleSourceQuery(EntitlementContext context, EntitlementSaleReservationPort.ReserveSale command) {
        public SaleSourceQuery { Objects.requireNonNull(context); Objects.requireNonNull(command); }
    }
    public static final String AUDIENCE = "/internal/core/entitlement/audience/resolve";
    public record SourceQuery(EntitlementContext context, SourceRef source) {
        public SourceQuery { Objects.requireNonNull(context); Objects.requireNonNull(source); }
    }
    public record AuthorizationQuery(EntitlementContext context, String authorizationRef) {
        public AuthorizationQuery { Objects.requireNonNull(context); ContractChecks.id(authorizationRef); }
    }
    public record GrantSourceQuery(EntitlementContext context, GrantCommand command) {
        public GrantSourceQuery { Objects.requireNonNull(context); Objects.requireNonNull(command); }
    }
    public record AudienceQuery(EntitlementContext context, String entityNo, String audienceFactRef) {
        public AudienceQuery {
            Objects.requireNonNull(context); ContractChecks.id(entityNo); ContractChecks.id(audienceFactRef);
        }
    }
}
