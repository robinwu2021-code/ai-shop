package ai.neargo.shop.retail.commerce.port;

import ai.neargo.shop.commerce.policy.StoreBusinessBoundaries;
import ai.neargo.shop.common.OpenHours;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.spi.commerce.CatalogAvailability;
import ai.neargo.shop.spi.commerce.CatalogAvailability.UtcWindow;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.user.StoreCardFacts;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Calendar evidence comes only from original store hours/status and original market timezone. */
public final class OriginalRetailCatalogAvailability
        implements RetailCatalogAvailabilityPort, ChannelAvailabilitySourcePort {
    private final StoreCardQueryPort stores;
    private final CatalogPriceSourcePort prices;
    private final MarketPort markets;
    private final Clock clock;

    public OriginalRetailCatalogAvailability(
            StoreCardQueryPort stores,
            CatalogPriceSourcePort prices,
            MarketPort markets,
            Clock clock) {
        this.stores = stores;
        this.prices = prices;
        this.markets = markets;
        this.clock = clock;
    }

    @Override
    public RetailCatalogAvailabilityPort.Facts capture(
            String entity, String store, List<RetailSelection> selections) {
        RetailSourceValidationProviderImpl.outsideTransaction();
        RetailSelection.key(entity);
        RetailSelection.key(store);
        if (selections == null || selections.isEmpty() || selections.size() > 100)
            throw new IllegalArgumentException("Missing selection");
        selections.forEach(
                s -> {
                    s.validate();
                    require(entity.equals(s.entityNo()) && store.equals(s.storeNo()));
                });
        var card = card(entity, store);
        var scope = new CatalogPriceSourcePort.MarketScope(entity, store);
        var market = prices.market(scope);
        CatalogPriceSourceFacts.validateMarketFacts(scope, market, clock.instant());
        var descriptor =
                markets.find(market.market().market())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing original market timezone"));
        require(
                descriptor.enabled()
                        && descriptor.market().equals(market.market().market())
                        && descriptor.currency().equals(market.market().currency())
                        && descriptor.currencyScale() == market.market().currencyScale()
                        && descriptor.timeZone() != null
                        && !descriptor.timeZone().isBlank());
        var zone = ZoneId.of(descriptor.timeZone());
        var now = clock.instant();
        var start = now.truncatedTo(ChronoUnit.DAYS);
        var end = start.plus(7, ChronoUnit.DAYS);
        var windows = new ArrayList<UtcWindow>();
        if (card.active()) {
            var times = OpenHours.boundaryTimes(card.openHours());
            LocalDate first = start.atZone(zone).toLocalDate().minusDays(1),
                    last = end.atZone(zone).toLocalDate();
            for (var day = first; !day.isAfter(last); day = day.plusDays(1)) {
                var from = boundary(day.atTime(times.getFirst()), zone);
                var to =
                        boundary(
                                day.plusDays(times.get(1).isAfter(times.getFirst()) ? 0 : 1)
                                        .atTime(times.get(1)),
                                zone);
                if (from.isBefore(start)) from = start;
                if (to.isAfter(end)) to = end;
                if (!from.isBefore(to)) continue;
                if (!windows.isEmpty() && !from.isAfter(windows.getLast().toExclusive())) {
                    var prior = windows.removeLast();
                    windows.add(
                            new UtcWindow(
                                    prior.fromInclusive(),
                                    to.isAfter(prior.toExclusive()) ? to : prior.toExclusive()));
                } else windows.add(new UtcWindow(from, to));
            }
        } else require(StoreDirectoryPort.STORE_READONLY.equals(card.status()));
        var after = card(entity, store);
        require(card.equals(after));
        var afterMarket = prices.market(scope);
        CatalogPriceSourceFacts.validateMarketFacts(scope, afterMarket, clock.instant());
        require(
                market.market().equals(afterMarket.market())
                        && markets.find(descriptor.market())
                                .filter(descriptor::equals)
                                .isPresent());
        Instant until = now.plusSeconds(30);
        if (market.validUntil().isBefore(until)) until = market.validUntil();
        if (afterMarket.validUntil().isBefore(until)) until = afterMarket.validUntil();
        require(until.isAfter(clock.instant()));
        String revision =
                PricingSnapshotHash.of(
                        List.of(
                                entity,
                                store,
                                card.status(),
                                card.openHours() == null ? "" : card.openHours(),
                                market.market(),
                                descriptor));
        var availability =
                new CatalogAvailability(
                        start,
                        end,
                        selections.stream()
                                .map(
                                        s ->
                                                new CatalogAvailability.SourceWindow(
                                                        s.selectionNo(), s.revisionNo(), windows))
                                .toList());
        return new RetailCatalogAvailabilityPort.Facts(
                entity,
                store,
                availability,
                revision,
                now,
                until,
                PricingSnapshotHash.of(List.of(entity, store, availability, revision, now, until)));
    }

    @Override
    public Kind kind() {
        return Kind.STORE;
    }

    @Override
    public Owner owner() {
        return Owner.RETAIL;
    }

    @Override
    public ChannelAvailabilitySourcePort.Facts capture(Query query) {
        RetailSourceValidationProviderImpl.outsideTransaction();
        require(
                query != null
                        && query.kind() == Kind.STORE
                        && query.owner() == Owner.RETAIL
                        && query.storeNo().equals(query.resourceRef()));
        var now = clock.instant();
        if (query.storeMode() != StoreMode.PHYSICAL_OPEN)
            return new ChannelAvailabilitySourcePort.Facts(
                    new Data(
                            query,
                            State.UNSUPPORTED,
                            null,
                            null,
                            "RETAIL_ACCEPTANCE_OWNER_UNSUPPORTED"),
                    now,
                    now.plusSeconds(30),
                    null);
        var card = card(query.entityNo(), query.storeNo());
        var scope = new CatalogPriceSourcePort.MarketScope(query.entityNo(), query.storeNo());
        var market = prices.market(scope);
        CatalogPriceSourceFacts.validateMarketFacts(scope, market, now);
        var descriptor =
                markets.find(market.market().market())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing original market timezone"));
        require(
                descriptor.enabled()
                        && descriptor.market().equals(market.market().market())
                        && descriptor.currency().equals(market.market().currency())
                        && descriptor.currencyScale() == market.market().currencyScale()
                        && descriptor.timeZone() != null);
        var zone = ZoneId.of(descriptor.timeZone());
        Function<Instant, State> predicate =
                at -> {
                    if (StoreDirectoryPort.STORE_READONLY.equals(card.status()))
                        return State.SUSPENDED;
                    Boolean open =
                            OpenHours.openAt(card.openHours(), at.atZone(zone).toLocalTime());
                    return open == null ? State.UNKNOWN : open ? State.OPEN : State.CLOSED;
                };
        State state = predicate.apply(now);
        Instant next = null;
        if (state != State.UNKNOWN && card.active())
            next =
                    StoreBusinessBoundaries.nextChange(
                            now,
                            List.of(zone),
                            OpenHours.boundaryTimes(card.openHours()),
                            List.of(),
                            predicate);
        var after = card(query.entityNo(), query.storeNo());
        var latestMarket = prices.market(scope);
        CatalogPriceSourceFacts.validateMarketFacts(scope, latestMarket, clock.instant());
        require(
                card.equals(after)
                        && market.market().equals(latestMarket.market())
                        && markets.find(descriptor.market()).filter(descriptor::equals).isPresent()
                        && predicate.apply(clock.instant()) == state);
        Instant until =
                market.validUntil().isBefore(latestMarket.validUntil())
                        ? market.validUntil()
                        : latestMarket.validUntil();
        if (now.plusSeconds(30).isBefore(until)) until = now.plusSeconds(30);
        if (next != null && next.isBefore(until)) until = next;
        require(until.isAfter(clock.instant()));
        String revision =
                PricingSnapshotHash.of(
                        List.of(card, market.market(), descriptor, query.storeMode()));
        var data = new Data(query, state, null, null, revision, next);
        return new ChannelAvailabilitySourcePort.Facts(
                data,
                now,
                until,
                PricingSnapshotHash.of(
                        List.of(data, market.receiptNo(), latestMarket.receiptNo())));
    }

    private StoreDirectoryPort.StoreCard card(String entity, String store) {
        var cards = stores.cards(List.of(store));
        StoreCardFacts.response(cards, List.of(store));
        var value = cards.get(store);
        require(value != null && entity.equals(value.entityNo()) && store.equals(value.storeNo()));
        return value;
    }

    private static Instant boundary(LocalDateTime local, ZoneId zone) {
        var offsets = zone.getRules().getValidOffsets(local);
        // Unknown or ambiguous local boundaries cannot be silently shifted into another sales
        // interval.
        require(offsets.size() == 1);
        return local.toInstant(offsets.getFirst());
    }

    private static void require(boolean ok) {
        if (!ok) throw new IllegalStateException("Invalid original retail calendar");
    }
}
