package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Facts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts.Query;
import ai.neargo.shop.spi.user.StoreCardFacts;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Objects;

/**
 * Original calendar interpretation is shared by local and remote readers rather than reimplemented
 * downstream.
 */
public final class StoreCalendarSourcePolicy {
    private StoreCalendarSourcePolicy() {}

    public static void validateQuery(Query q) {
        if (q == null
                || !StoreCardFacts.key(q.entityNo())
                || !StoreCardFacts.key(q.storeNo())
                || q.fromDate() == null
                || q.toDate() == null
                || q.fromDate().getYear() < 1900
                || q.toDate().getYear() > 9998
                || q.toDate().isBefore(q.fromDate())
                || ChronoUnit.DAYS.between(q.fromDate(), q.toDate()) > 30)
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    public static Instant lower(Query q) {
        validateQuery(q);
        return q.fromDate().minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static Instant upper(Query q) {
        validateQuery(q);
        return q.toDate().plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static Facts seal(Facts f) {
        return copy(f, PricingSnapshotHash.of(copy(f, null)));
    }

    private static Facts copy(Facts f, String receipt) {
        return new Facts(
                f.schemaVersion(),
                f.purpose(),
                f.query(),
                f.store(),
                f.policies(),
                f.exceptions(),
                f.verifiedAt(),
                f.validUntil(),
                receipt);
    }

    public static void validate(Query q, Facts f, Instant now) {
        validateQuery(q);
        require(
                f != null
                        && Objects.equals(f.schemaVersion(), 1)
                        && "STORE_CALENDAR".equals(f.purpose())
                        && q.equals(f.query())
                        && f.store() != null
                        && q.entityNo().equals(f.store().entityNo())
                        && q.storeNo().equals(f.store().storeNo())
                        && ("ACTIVE".equals(f.store().status())
                                || "READONLY".equals(f.store().status()))
                        && (f.store().openHours() == null || f.store().openHours().length() <= 512)
                        && f.policies() != null
                        && !f.policies().isEmpty()
                        && f.policies().size() <= 257
                        && f.exceptions() != null
                        && f.exceptions().size() <= 31);
        Instant previous = null;
        var ids = new HashSet<String>();
        for (int n = 0; n < f.policies().size(); n++) {
            var p = f.policies().get(n);
            require(
                    p != null
                            && StoreCardFacts.key(p.policyNo())
                            && ids.add(p.policyNo())
                            && p.timezone() != null
                            && p.cutoverMinute() != null
                            && p.cutoverMinute() >= 0
                            && p.cutoverMinute() < 1440
                            && version(p.version())
                            && p.effectiveFrom() != null
                            && !p.effectiveFrom().isAfter(upper(q))
                            && (previous == null || p.effectiveFrom().isAfter(previous))
                            && (n == 0 || p.effectiveFrom().isAfter(lower(q))));
            try {
                ZoneId.of(p.timezone());
            } catch (RuntimeException invalid) {
                throw malformed();
            }
            previous = p.effectiveFrom();
        }
        LocalDate date = null;
        var calendarIds = new HashSet<String>();
        for (var e : f.exceptions()) {
            require(
                    e != null
                            && StoreCardFacts.key(e.calendarNo())
                            && calendarIds.add(e.calendarNo())
                            && StoreCardFacts.key(e.savedPolicyNo())
                            && version(e.version())
                            && e.businessDate() != null
                            && !e.businessDate().isBefore(q.fromDate())
                            && !e.businessDate().isAfter(q.toDate())
                            && (date == null || e.businessDate().isAfter(date))
                            && e.intervals() != null
                            && e.intervals().size() <= 32);
            try {
                require(
                        ServiceCalendarPolicy.normalize(e.mode(), e.intervals())
                                .equals(e.intervals()));
            } catch (RuntimeException invalid) {
                throw malformed();
            }
            date = e.businessDate();
        }
        require(
                now != null
                        && f.verifiedAt() != null
                        && !f.verifiedAt().isAfter(now)
                        && f.validUntil() != null
                        && f.validUntil().isAfter(now)
                        && !f.validUntil().isAfter(f.verifiedAt().plusSeconds(30))
                        && f.receiptNo() != null
                        && seal(f).receiptNo().equals(f.receiptNo()));
    }

    /**
     * Absent policy, unknown hours, closed stores and dates outside the captured business range
     * deny acceptance.
     */
    public static boolean acceptsAt(Facts f, Instant at, Instant observedAt) {
        require(f != null && at != null);
        validate(f.query(), f, observedAt);
        var selected =
                f.policies().stream()
                        .filter(p -> !p.effectiveFrom().isAfter(at))
                        .reduce((one, two) -> two);
        if (selected.isEmpty()) return false;
        var p = selected.get();
        var date = ServiceCalendarPolicy.businessDate(at, p.timezone(), p.cutoverMinute());
        if (date.isBefore(f.query().fromDate()) || date.isAfter(f.query().toDate())) return false;
        var exception =
                f.exceptions().stream().filter(e -> date.equals(e.businessDate())).findFirst();
        var rule =
                exception
                        .map(
                                e ->
                                        new ServiceCalendarPolicy.DayRule(
                                                e.businessDate(), e.mode(), e.intervals()))
                        .orElse(null);
        return ServiceCalendarPolicy.acceptsAt(
                f.store().status(),
                f.store().openHours(),
                p.timezone(),
                p.cutoverMinute(),
                at,
                rule);
    }

    private static boolean version(Long v) {
        return v != null && v >= 0 && v <= 9007199254740991L;
    }

    private static void require(boolean ok) {
        if (!ok) throw malformed();
    }

    private static IllegalStateException malformed() {
        return new IllegalStateException("Invalid original calendar facts");
    }
}
