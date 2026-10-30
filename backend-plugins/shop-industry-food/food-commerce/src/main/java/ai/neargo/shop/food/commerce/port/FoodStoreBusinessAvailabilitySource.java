package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.commerce.policy.StoreBusinessBoundaries;
import ai.neargo.shop.common.OpenHours;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts;
import ai.neargo.shop.food.policy.StoreCalendarSourcePolicy;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

/**
 * Original FOOD acceptance exceptions/cutovers never become physical opening hours or inventory
 * balances.
 */
public final class FoodStoreBusinessAvailabilitySource implements ChannelAvailabilitySourcePort {
    private final StoreCalendarSourceService calendars;
    private final Clock clock;

    public FoodStoreBusinessAvailabilitySource(StoreCalendarSourceService calendars, Clock clock) {
        this.calendars = calendars;
        this.clock = clock;
    }

    @Override
    public Kind kind() {
        return Kind.STORE;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Facts capture(Query query) {
        require(
                query != null
                        && query.kind() == Kind.STORE
                        && query.owner() == Owner.FOOD
                        && query.storeMode() != null
                        && query.storeNo().equals(query.resourceRef())
                        && !TransactionSynchronizationManager.isActualTransactionActive());
        var now = clock.instant();
        var date = now.atOffset(ZoneOffset.UTC).toLocalDate();
        var q =
                new StoreCalendarSourceContracts.Query(
                        query.entityNo(), query.storeNo(), date.minusDays(2), date.plusDays(10));
        var facts = calendars.capture(q);
        var prepared = StoreCalendarSourcePolicy.prepare(q, facts, now);
        Function<Instant, State> predicate =
                at -> {
                    var policy = StoreCalendarSourcePolicy.policyAt(prepared, at);
                    if (policy == null) return State.UNKNOWN;
                    if ("READONLY".equals(facts.store().status())) return State.SUSPENDED;
                    var open =
                            OpenHours.openAt(
                                    facts.store().openHours(),
                                    at.atZone(ZoneId.of(policy.timezone())).toLocalTime());
                    if (open == null) return State.UNKNOWN;
                    if (query.storeMode() == StoreMode.PHYSICAL_OPEN)
                        return open ? State.OPEN : State.CLOSED;
                    return StoreCalendarSourcePolicy.acceptsAt(prepared, at)
                            ? State.OPEN
                            : State.SUSPENDED;
                };
        State state = predicate.apply(now);
        Instant next = null;
        if (state != State.UNKNOWN) {
            var times = new HashSet<LocalTime>();
            times.add(LocalTime.MIDNIGHT);
            if (OpenHours.openAt(facts.store().openHours(), LocalTime.MIDNIGHT) != null)
                times.addAll(OpenHours.boundaryTimes(facts.store().openHours()));
            facts.policies()
                    .forEach(
                            p ->
                                    times.add(
                                            LocalTime.of(
                                                    p.cutoverMinute() / 60,
                                                    p.cutoverMinute() % 60)));
            facts.exceptions()
                    .forEach(
                            e ->
                                    e.intervals()
                                            .forEach(
                                                    i -> times.addAll(OpenHours.boundaryTimes(i))));
            next =
                    StoreBusinessBoundaries.nextChange(
                            now,
                            facts.policies().stream()
                                    .map(p -> ZoneId.of(p.timezone()))
                                    .distinct()
                                    .toList(),
                            List.copyOf(times),
                            facts.policies().stream()
                                    .map(StoreCalendarSourceContracts.Policy::effectiveFrom)
                                    .toList(),
                            predicate);
        }
        var after = calendars.capture(q);
        StoreCalendarSourcePolicy.validate(q, after, clock.instant());
        require(
                facts.store().equals(after.store())
                        && facts.policies().equals(after.policies())
                        && facts.exceptions().equals(after.exceptions())
                        && predicate.apply(clock.instant()) == state);
        String revision =
                PricingSnapshotHash.of(
                        List.of(
                                facts.store(),
                                facts.policies(),
                                facts.exceptions(),
                                query.storeMode()));
        Instant until =
                facts.validUntil().isBefore(after.validUntil())
                        ? facts.validUntil()
                        : after.validUntil();
        if (next != null && next.isBefore(until)) until = next;
        require(until.isAfter(clock.instant()));
        return new Facts(
                new Data(query, state, null, null, revision, next), now, until, facts.receiptNo());
    }

    private static void require(boolean valid) {
        if (!valid)
            throw new IllegalStateException("Invalid original FOOD store business evidence");
    }
}
