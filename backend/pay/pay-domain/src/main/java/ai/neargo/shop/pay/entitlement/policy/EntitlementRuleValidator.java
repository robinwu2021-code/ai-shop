package ai.neargo.shop.pay.entitlement.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Set;
import java.util.List;

/** Validate combinations before storing drafts; paid cards can only be issued against paid-order facts. */
public final class EntitlementRuleValidator {
    public static final Set<String> KINDS = Set.of("ORDER_COUPON", "STORE_EXCHANGE", "PAID_CARD");
    public static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "PAUSED", "ENDED");
    private static final Set<String> ISSUE_MODES = Set.of("CENTER", "TARGETED", "ACTIVITY", "PAID_ORDER");

    /** Reject unsupported or ambiguous definitions; issued instances retain validated version snapshots. */
    public void validate(EntitlementDefinition d) {
        require(d != null);
        require(text(d.title(), 100) && d.kind() != null && KINDS.contains(d.kind()));
        require("MERCHANT".equals(d.funder()) || "PLATFORM".equals(d.funder()));
        require(d.currency() != null && d.currency().matches("[A-Z]{3}"));
        try {
            Currency.getInstance(d.currency());
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        require(d.benefitMode() != null);
        require(d.minAmountMinor() == null || d.minAmountMinor() >= 0);
        require(d.minQty() == null || d.minQty() > 0);
        require(d.benefitCapMinor() == null || d.benefitCapMinor() >= 0);
        require(d.allowedIssueModes() != null && !d.allowedIssueModes().isEmpty());
        require(d.allowedIssueModes().stream().allMatch(ISSUE_MODES::contains));
        require(d.allowedIssueModes().stream().distinct().count() == d.allowedIssueModes().size());
        if ("PAID_CARD".equals(d.kind())) {
            require("MERCHANT".equals(d.funder()) && "SERVICE_UNITS".equals(d.benefitMode()));
            require(d.allowedIssueModes().equals(List.of("PAID_ORDER")));
        } else {
            require(!d.allowedIssueModes().contains("PAID_ORDER"));
        }
        if ("ORDER_COUPON".equals(d.kind())) {
            require(Set.of("CASH", "PERCENT", "FREE_SHIP").contains(d.benefitMode()));
            require("COUNT".equals(d.usageModel()));
        } else if ("STORE_EXCHANGE".equals(d.kind())) {
            require("GIFT".equals(d.benefitMode()) || "SERVICE_UNITS".equals(d.benefitMode()));
        }
        if ("CASH".equals(d.benefitMode())) {
            require(d.benefitValue() != null && d.benefitValue() > 0);
        } else if ("PERCENT".equals(d.benefitMode())) {
            require(d.benefitValue() != null && d.benefitValue() > 0 && d.benefitValue() <= 10000);
        } else if ("GIFT".equals(d.benefitMode())) {
            require(text(d.benefitRef(), 64));
        }
        require(d.usageModel() != null && Set.of("COUNT", "PERIOD_COUNT", "PERIOD_DAILY").contains(d.usageModel()));
        if ("PERIOD_DAILY".equals(d.usageModel())) {
            require("PAID_CARD".equals(d.kind()) && d.totalUnits() == null
                    && d.dailyUnits() != null && d.dailyUnits() > 0);
        } else {
            require(d.totalUnits() != null && d.totalUnits() > 0 && d.dailyUnits() == null);
        }
        var v = d.validity();
        require(v != null && v.mode() != null && Set.of("ABSOLUTE", "RELATIVE").contains(v.mode()));
        require(v.startBasis() != null && Set.of("GRANTED", "PAID", "FIRST_USE").contains(v.startBasis()));
        require(v.periodBasis() != null && Set.of("ROLLING_DAYS", "CALENDAR_MONTH").contains(v.periodBasis()));
        require(text(v.zone(), 64));
        try {
            ZoneId.of(v.zone());
        } catch (DateTimeException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if ("ABSOLUTE".equals(v.mode())) {
            require(v.startAt() != null && v.endAt() != null && v.startAt().isBefore(v.endAt()));
            require(v.validDays() == null && !"FIRST_USE".equals(v.startBasis()));
        } else {
            require(v.startAt() == null && v.endAt() == null);
            if ("ROLLING_DAYS".equals(v.periodBasis())) {
                require(v.validDays() != null && v.validDays() > 0);
            } else {
                require(v.validDays() == null);
            }
        }
        if ("PAID_CARD".equals(d.kind())) {
            require(!"GRANTED".equals(v.startBasis()));
        } else {
            require(!"PAID".equals(v.startBasis()));
        }
        var scope = d.redemptionScope();
        require(scope != null && scope.mode() != null && scope.refs() != null);
        require(Set.of("ALL", "STORES", "GOODS", "CATEGORIES").contains(scope.mode()));
        require("ALL".equals(scope.mode()) ? scope.refs().isEmpty() : !scope.refs().isEmpty());
        require(scope.refs().stream().allMatch(ref -> text(ref, 64)));
        require(scope.refs().stream().distinct().count() == scope.refs().size());
    }

    /** Expired absolute definitions remain readable, but cannot acquire a new publication or binding. */
    public void validatePublicationTime(EntitlementDefinition definition, Instant at) {
        if ("ABSOLUTE".equals(definition.validity().mode())
                && !definition.validity().endAt().isAfter(at)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private boolean text(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit && value.equals(value.strip());
    }

    private void require(boolean valid) {
        if (!valid) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
