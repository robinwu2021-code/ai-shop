package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import java.util.Objects;

/** Execution limits only; never an asset quote, balance, authorization or publication evidence. */
public final class PricingFundingCapabilities {
    private PricingFundingCapabilities() { }
    public enum Family { ORDER_COUPON, STORED_VALUE }
    public enum Status { SUPPORTED, TENANT_UNSUPPORTED, SOURCE_UNSUPPORTED, CURRENCY_UNSUPPORTED,
        DISABLED, OWNER_UNAVAILABLE }
    public record Scope(String tenantNo, String entityNo, String storeNo, String sourceSystem,
            String sourceType, String currency) {
        public Scope {
            PricingSourceFacts.key(tenantNo); PricingSourceFacts.key(entityNo); PricingSourceFacts.key(storeNo);
            PricingSourceFacts.key(sourceSystem); PricingSourceFacts.key(sourceType);
            require(currency != null && currency.matches("[A-Z]{3}"));
        }
    }
    public record Snapshot(int schemaVersion, Family family, Scope scope, Status status,
            String protocol, PricingCapabilities capabilities, String snapshotHash) {
        public Snapshot {
            require(schemaVersion == 1 && family != null && scope != null && status != null);
            require(PricingFundingCapabilities.protocol(family).equals(protocol));
            require(snapshotHash != null && snapshotHash.matches("[0-9a-f]{64}"));
            if (status == Status.SUPPORTED) {
                require("MAIN".equals(scope.tenantNo()) && "CORE".equals(scope.sourceSystem())
                        && "CNY".equals(scope.currency()) && sourceType(family).equals(scope.sourceType())
                        && limits(family).equals(capabilities));
            } else require(capabilities == null);
        }
    }
    public static String protocol(Family family) {
        return switch (Objects.requireNonNull(family)) {
            case ORDER_COUPON -> "ORDER_COUPON_SINGLE_V1";
            case STORED_VALUE -> "SERVICE_STORED_FULL_V1";
        };
    }
    public static String sourceType(Family family) {
        return family == Family.ORDER_COUPON ? "ORDER_USE" : "SERVICE_USE";
    }
    public static PricingCapabilities limits(Family family) {
        Objects.requireNonNull(family);
        return family == Family.ORDER_COUPON ? new PricingCapabilities(1, 1, false, false, false)
                : new PricingCapabilities(0, 0, false, false, true);
    }
    public static Snapshot freeze(Family family, Scope scope, Status status) {
        Objects.requireNonNull(status);
        var capabilities = status == Status.SUPPORTED ? limits(family) : null;
        String protocol = protocol(family);
        String hash = digest(1, family, scope, status, protocol, capabilities);
        return new Snapshot(1, family, scope, status, protocol, capabilities, hash);
    }
    public static void validate(Snapshot value, Family family, Scope scope) {
        require(value != null && value.family() == family && scope.equals(value.scope()));
        require(value.snapshotHash().equals(digest(value.schemaVersion(), value.family(), value.scope(),
                value.status(), value.protocol(), value.capabilities())));
    }
    private record Payload(int schemaVersion, Family family, Scope scope, Status status,
            String protocol, PricingCapabilities capabilities) { }
    private static String digest(int version, Family family, Scope scope, Status status,
            String protocol, PricingCapabilities capabilities) {
        return PricingSnapshotHash.of(new Payload(version, family, scope, status, protocol, capabilities));
    }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Explicit owner pricing capability protocol required");
    }
}
