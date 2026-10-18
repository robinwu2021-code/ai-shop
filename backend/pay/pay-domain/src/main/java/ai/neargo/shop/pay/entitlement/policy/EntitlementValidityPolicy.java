package ai.neargo.shop.pay.entitlement.policy;

import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

/** Pure time rules shared by publication and activation; calculating a preview never activates an instance. */
public final class EntitlementValidityPolicy {
    /** Validate the same rule shape used later to calculate immutable instance windows. */
    public void validate(Validity rule) {
        require(rule != null && rule.mode() != null && Set.of("ABSOLUTE", "RELATIVE").contains(rule.mode()));
        require(rule.startBasis() != null && Set.of("PAID", "GRANTED", "FIRST_USE").contains(rule.startBasis()));
        require(rule.periodBasis() != null && Set.of("ROLLING_DAYS", "CALENDAR_MONTH").contains(rule.periodBasis()));
        require(rule.zone() != null && !rule.zone().isBlank() && rule.zone().length() <= 64
                && rule.zone().equals(rule.zone().strip()));
        ZoneId.of(rule.zone());
        if ("ABSOLUTE".equals(rule.mode())) {
            require(rule.startAt() != null && rule.endAt() != null && rule.startAt().isBefore(rule.endAt())
                    && rule.validDays() == null && !"FIRST_USE".equals(rule.startBasis()));
        } else {
            require(rule.startAt() == null && rule.endAt() == null);
            require("ROLLING_DAYS".equals(rule.periodBasis())
                    ? rule.validDays() != null && rule.validDays() > 0 : rule.validDays() == null);
        }
    }

    /**
     * Natural-month rights start at the actual basis instant and end at next month's local midnight.
     * Rolling days preserve local wall-clock time across DST, rather than assuming every day is 24 hours.
     */
    public EffectiveWindow calculate(Validity rule, Instant paidAt, Instant grantedAt, Instant firstUseAt) {
        validate(rule);
        if ("ABSOLUTE".equals(rule.mode())) {
            return new EffectiveWindow(rule.startAt(), rule.endAt(), false);
        }
        Instant from = switch (rule.startBasis()) {
            case "PAID" -> paidAt;
            case "GRANTED" -> grantedAt;
            case "FIRST_USE" -> firstUseAt;
            default -> throw new IllegalArgumentException("Unsupported start basis");
        };
        if (from == null && "FIRST_USE".equals(rule.startBasis())) {
            return new EffectiveWindow(null, null, true);
        }
        require(from != null);
        if ("FIRST_USE".equals(rule.startBasis())) {
            require((paidAt == null || !from.isBefore(paidAt)) && (grantedAt == null || !from.isBefore(grantedAt)));
        }
        ZoneId zone = ZoneId.of(rule.zone());
        var local = from.atZone(zone);
        try {
            Instant until = "CALENDAR_MONTH".equals(rule.periodBasis())
                    ? local.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant()
                    : local.plusDays(rule.validDays()).toInstant();
            return new EffectiveWindow(from, until, false);
        } catch (DateTimeException | ArithmeticException invalidRange) {
            throw new IllegalArgumentException("Validity window exceeds supported time range", invalidRange);
        }
    }

    /** The persisted window is half-open; an unactivated preview is never usable. */
    public record EffectiveWindow(Instant from, Instant until, boolean pendingActivation) {
        public EffectiveWindow {
            require(pendingActivation ? from == null && until == null
                    : from != null && until != null && from.isBefore(until));
        }

        public boolean contains(Instant instant) {
            require(instant != null);
            return !pendingActivation && !instant.isBefore(from) && instant.isBefore(until);
        }
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid entitlement validity rule or source time");
        }
    }
}
