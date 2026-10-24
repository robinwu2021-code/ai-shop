package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.List;
import java.util.Set;

/** Immutable policy facts; store and management bindings must be resolved by Core, never by public input. */
public final class StoredValuePolicies {
    private StoredValuePolicies() { }
    public static final Set<String> TARGET_TYPES = Set.of("SPACE", "BRAND_STORES", "STORE");

    public record StoreScope(String tenantNo, String managementNo, String storeNo, String brandNo, String currency) {
        public StoreScope {
            ContractChecks.id(tenantNo); ContractChecks.id(managementNo); ContractChecks.id(storeNo);
            ContractChecks.optionalId(brandNo); ContractChecks.currency(currency);
        }
    }
    public record Rules(boolean allowIssue, boolean requireApproval, int maxBonusBps, long maxPaidMinor,
            long maxBonusMinor, int maxActiveDays, long maxOutstandingMinor) {
        public Rules {
            ContractChecks.require(maxBonusBps >= 0 && maxBonusBps <= 10000 && maxPaidMinor > 0
                    && maxBonusMinor >= 0 && maxActiveDays > 0 && maxOutstandingMinor > 0);
        }
    }
    public record PolicyVersion(String tenantNo, String managementNo, String policyNo, String versionNo,
            String targetType, String targetNo, String currency, Rules rules) {
        public PolicyVersion {
            ContractChecks.id(tenantNo); ContractChecks.id(managementNo); ContractChecks.id(policyNo);
            ContractChecks.id(versionNo); ContractChecks.id(targetNo); ContractChecks.currency(currency);
            ContractChecks.require(TARGET_TYPES.contains(targetType)); ContractChecks.present(rules);
        }
    }
    /** Explicit resource permission already checked by Core; not a public request DTO. */
    public record PolicyAuthority(String tenantNo, String actorNo, String managementNo, String targetType,
            String targetNo, String brandNo, String currency, long authorityRevision) {
        public PolicyAuthority {
            ContractChecks.id(tenantNo); ContractChecks.id(actorNo); ContractChecks.id(managementNo);
            ContractChecks.id(targetNo); ContractChecks.optionalId(brandNo); ContractChecks.currency(currency);
            ContractChecks.require(TARGET_TYPES.contains(targetType) && authorityRevision >= 0);
            ContractChecks.require(!"SPACE".equals(targetType) || managementNo.equals(targetNo));
            ContractChecks.require("STORE".equals(targetType) || brandNo == null);
        }
    }
    public record PolicyEdit(Rules rules, long expectedRevision) {
        public PolicyEdit { ContractChecks.present(rules); ContractChecks.require(expectedRevision >= 0); }
    }
    public record PolicyView(String policyNo, String versionNo, long revision, String targetType, String targetNo,
            String currency, Rules rules, List<String> parentVersionRefs, String contentHash) {
        public PolicyView { parentVersionRefs = List.copyOf(parentVersionRefs); ContractChecks.hash(contentHash); }
    }
    public record EffectivePolicy(StoreScope scope, Rules rules, List<PolicyVersion> sources, String hash,
            boolean configured) {
        public EffectivePolicy {
            ContractChecks.present(scope); ContractChecks.present(rules); sources = List.copyOf(sources);
            ContractChecks.hash(hash);
        }
    }
    /** Capacity counters must be read under the existing Pay quota lock when authorizing an actual issuance. */
    public record IssueRequest(long paidMinor, long bonusMinor, int activeDays, long outstandingMinor,
            long reservedMinor) {
        public IssueRequest {
            ContractChecks.require(paidMinor > 0 && bonusMinor >= 0 && activeDays > 0
                    && outstandingMinor >= 0 && reservedMinor >= 0);
        }
    }
    public record Decision(boolean allowed, boolean approvalRequired, String policyHash, List<String> blockers) {
        public Decision { ContractChecks.hash(policyHash); blockers = List.copyOf(blockers); }
    }
}
