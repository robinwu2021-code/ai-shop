package ai.neargo.commerce.core.subscription;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.subscription.*;
import ai.neargo.commerce.core.spi.SubscriptionRepository;

import java.util.*;

final class SubscriptionViews {
    static SubscriptionView current(SubscriptionRepository repo, StoreRef store, long at) {
        var account = repo.lock(store);
        var periods = repo.periods(store);
        var codes = new TreeSet<String>();
        periods.forEach(p -> codes.addAll(p.ends().keySet()));
        var segments = codes.stream().flatMap(c -> repo.segments(store, c).stream()).toList();
        var sources = new ArrayList<EntitlementSource>();
        for (var p : periods) {
            String code = p.fixed() ? null : p.ends().keySet().iterator().next();
            long consumed = 0;
            if (p.fixed() && p.duration() != null && at > p.start()) {
                consumed = Math.subtractExact(Math.min(at, p.end()), p.start());
            } else if (!p.fixed())
                for (var s : segments)
                    if (s.sourceNo().equals(p.no()) && s.startsAt().toEpochMilli() < at)
                        consumed =
                                Math.addExact(
                                        consumed,
                                        Math.subtractExact(
                                                Math.min(at, s.endsAt().toEpochMilli()),
                                                s.startsAt().toEpochMilli()));
            sources.add(
                    new EntitlementSource(
                            p.no(),
                            code,
                            Optional.ofNullable(p.planVersion()),
                            p.origin(),
                            p.duration() == null ? 0 : p.duration(),
                            consumed,
                            new Money(p.currency(), p.basis()),
                            p.duration() == null,
                            p.lifecycle(),
                            p.revokedDuration()));
        }
        return new SubscriptionView(
                store, account.entitlementVersion(), List.copyOf(sources), segments);
    }
}
