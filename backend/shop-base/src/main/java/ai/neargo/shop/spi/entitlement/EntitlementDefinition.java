package ai.neargo.shop.spi.entitlement;

import java.time.Instant;
import java.util.List;

/** Immutable rights definition shared by catalog references; product sale price remains in core. */
public record EntitlementDefinition(String title, String kind, String funder, String currency,
                                    String benefitMode, Long benefitValue, Long benefitCapMinor,
                                    String benefitRef, Long minAmountMinor, Integer minQty,
                                    String usageModel, Integer totalUnits, Integer dailyUnits,
                                    Validity validity, RedemptionScope redemptionScope,
                                    List<String> allowedIssueModes) {
    public EntitlementDefinition {
        allowedIssueModes = allowedIssueModes == null ? null : List.copyOf(allowedIssueModes);
    }

    /** All absolute times are instants; market zone is used only for calendar/service-day rules. */
    public record Validity(String mode, Instant startAt, Instant endAt, Integer validDays,
                           String startBasis, String periodBasis, String zone) {
    }

    /** Participation scope belongs to activities; this scope limits use of the acquired rights. */
    public record RedemptionScope(String mode, List<String> refs) {
        public RedemptionScope {
            refs = refs == null ? null : List.copyOf(refs);
        }
    }
}
