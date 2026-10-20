package ai.neargo.shop.pay.migration.service;

import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Pure migration baseline calculation; no budget expansion, source repair, or recipient identifiers in the result. */
public final class LegacyCouponIssuanceAssessor {
    public Assessment assess(JsonNode manifest, EntitlementDefinition definition,
                             List<LegacyCouponMigrationNormalizer.InstancePlan> instances, Instant at) {
        var blockers = new LinkedHashSet<String>();
        var root = manifest.path("template");
        Long issued = number(root, "receivedCount");
        Long total = number(root, "totalCount");
        Long budget = number(root, "budgetMinor");
        Long perUser = number(root, "perUserLimit");
        boolean legacy = "MKT_LEGACY".equals(manifest.path("source").path("sourceSystem").asText());
        if (!legacy && !"PMT".equals(manifest.path("source").path("sourceSystem").asText())) {
            blockers.add("UNSUPPORTED_SOURCE");
        }
        // MKT zero is unlimited; PMT zero is an actual zero ceiling, while NULL is unlimited.
        boolean unlimited = legacy ? Long.valueOf(0).equals(total) : root.path("totalCount").isNull();
        if ((!unlimited && (total == null || total < 0)) || (budget == null && !root.path("budgetMinor").isNull()) || (budget != null && budget < 0)
                || perUser == null || perUser < 0 || perUser > Integer.MAX_VALUE || issued == null || issued < 0) {
            blockers.add("INVALID_ISSUANCE_FACTS");
        }
        Long limit = unlimited ? null : total;
        Long maxExposure = budget != null && budget > 0 ? budget : null;
        int effectivePerUser = perUser != null && perUser >= 0 && perUser <= Integer.MAX_VALUE
                ? Math.max(perUser.intValue(), 1) : 1;
        long observed = manifest.path("instances").size();
        if (!manifest.path("instances").isArray() || instances.size() != observed
                || issued == null || issued != observed) blockers.add("ISSUANCE_COUNTER_MISMATCH");
        if (limit != null && issued != null && issued > limit) blockers.add("ISSUANCE_COUNT_EXCEEDS_LIMIT");
        var recipients = new HashMap<String, Long>();
        for (var item : manifest.path("instances")) {
            String user = item.path("userNo").asText();
            if (user.isBlank()) blockers.add("INVALID_RECIPIENT_FACT");
            else recipients.merge(user, 1L, Long::sum);
        }
        if (recipients.values().stream().anyMatch(count -> count > effectivePerUser)) {
            blockers.add("RECIPIENT_LIMIT_EXCEEDED");
        }
        Long perUse = definition == null ? null : "CASH".equals(definition.benefitMode()) ? definition.benefitValue()
                : "PERCENT".equals(definition.benefitMode()) ? definition.benefitCapMinor() : null;
        Long lifetime = null, remaining = null;
        if (perUse == null || perUse <= 0 || definition == null || definition.totalUnits() == null
                || definition.totalUnits() <= 0) {
            blockers.add("FINITE_ISSUANCE_EXPOSURE_UNAVAILABLE");
        } else {
            try {
                if (issued != null && issued >= 0) {
                    lifetime = Math.multiplyExact(Math.multiplyExact(perUse, definition.totalUnits().longValue()), issued);
                    if (maxExposure != null && lifetime > maxExposure) blockers.add("ISSUANCE_EXPOSURE_EXCEEDS_BUDGET");
                }
                long units = 0;
                for (var instance : instances) {
                    if (!instance.archivalOnly() && "UNUSED".equals(instance.sourceStatus())
                            && instance.effectiveUntil() != null && at.isBefore(instance.effectiveUntil())) {
                        units = Math.addExact(units, instance.remainingUnits());
                    }
                }
                remaining = Math.multiplyExact(perUse, units);
            } catch (ArithmeticException overflow) {
                lifetime = null; remaining = null;
                blockers.add("ISSUANCE_EXPOSURE_OVERFLOW");
            }
        }
        long archival = instances.stream().filter(LegacyCouponMigrationNormalizer.InstancePlan::archivalOnly).count();
        return new Assessment(issued, observed, limit, effectivePerUser, maxExposure, lifetime, remaining,
                archival, recipients.size(), List.copyOf(blockers));
    }
    private static Long number(JsonNode root, String field) {
        var value = root.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
    }
    /** Lifetime exposure is an issuance promise; it must never be presented as historical consumption. */
    public record Assessment(Long sourceIssuedCount, long observedInstanceCount, Long limitCount, int perUserLimit,
                             Long maxExposureMinor, Long lifetimeIssuedExposureMinor, Long activeRemainingExposureMinor,
                             long archivalInstanceCount, long recipientCount, List<String> blockers) {
        public Assessment { blockers = List.copyOf(blockers); }
    }
}
