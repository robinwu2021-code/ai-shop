package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.commerce.policy.CatalogReplacementCapture;
import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.commerce.assembler.FoodCatalogAssembler;
import ai.neargo.shop.food.commerce.dto.FoodCatalogBindingContracts;
import ai.neargo.shop.food.commerce.dto.FoodCatalogPricePolicyContracts;
import ai.neargo.shop.food.commerce.policy.FoodCatalogAvailabilityPolicy;
import ai.neargo.shop.food.commerce.policy.FoodCatalogPricePolicy;
import ai.neargo.shop.food.commerce.service.FoodCatalogPricePolicyService;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.dto.StoreCalendarSourceContracts;
import ai.neargo.shop.food.policy.MenuSelectionFeasibilityPolicy;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.SelectionFeasibility;
import ai.neargo.shop.food.policy.StoreCalendarSourcePolicy;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.StoreCalendarSourceService;
import ai.neargo.shop.spi.commerce.CatalogAvailability;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;
import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import ai.neargo.shop.spi.media.MediaPublicSourcePort;
import ai.neargo.shop.spi.product.CatalogImageContracts;
import ai.neargo.shop.spi.product.CatalogImageFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts;
import ai.neargo.shop.spi.product.CatalogMarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Complete original-owner evidence is captured outside transactions; a second read rejects
 * mid-capture changes.
 */
public final class FoodCatalogSourcePortImpl implements ChannelCatalogSourcePort {
    private final ConfigRevisionService menus;
    private final ChannelCatalogAuthorityService authorities;
    private final FoodCatalogPricePolicyService prices;
    private final StoreCalendarSourceService calendars;
    private final GoodsCatalogQueryPort goods;
    private final MediaPublicSourcePort media;
    private final ObjectMapper json;
    private final Clock clock;
    private boolean withdrawalEnabled;
    private Supplier<CatalogPriceSourcePort> scopePrices = () -> null;
    private Supplier<ChannelSourceWithdrawalProvider> withdrawals = () -> null;

    public void withdrawalSupport(
            boolean enabled,
            Supplier<CatalogPriceSourcePort> prices,
            Supplier<ChannelSourceWithdrawalProvider> sources) {
        withdrawalEnabled = enabled;
        scopePrices = prices;
        withdrawals = sources;
    }

    /**
     * No provider can invent successful identity, media or prices when the corresponding owner is
     * absent.
     */
    public FoodCatalogSourcePortImpl(
            ConfigRevisionService menus,
            ChannelCatalogAuthorityService authorities,
            FoodCatalogPricePolicyService prices,
            StoreCalendarSourceService calendars,
            GoodsCatalogQueryPort goods,
            MediaPublicSourcePort media,
            ObjectMapper json,
            Clock clock) {
        this.menus = menus;
        this.authorities = authorities;
        this.prices = prices;
        this.calendars = calendars;
        this.goods = goods;
        this.media = media;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Document capture(CaptureCommand command) {
        validateCommand(command);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "Complete catalog capture requires owner reads outside a transaction");
        require(
                command.withdrawals() == null
                        || command.withdrawals().isEmpty()
                        || withdrawalEnabled);
        if (command.sources().isEmpty()) {
            require(withdrawalEnabled);
            return CatalogReplacementCapture.enrich(
                    command, null, authorities, scopePrices.get(), withdrawals.get(), json, clock);
        }
        Instant started = clock.instant();
        var deadlines = new ArrayList<Instant>();
        deadlines.add(started.plusSeconds(30));
        var finalReads = new ArrayList<Runnable>();
        var authorityQuery =
                new ChannelCatalogAuthorityContracts.Query(
                        command.entityNo(),
                        command.storeNo(),
                        command.connectionNo(),
                        command.connectionRevisionNo());
        var authority = authorities.capture(authorityQuery);
        ChannelCatalogAuthorityPolicy.validate(authorityQuery, authority, clock.instant());
        deadlines.add(authority.validUntil());
        var day = started.truncatedTo(ChronoUnit.DAYS).atOffset(ZoneOffset.UTC).toLocalDate();
        var calendarQuery =
                new StoreCalendarSourceContracts.Query(
                        command.entityNo(), command.storeNo(), day.minusDays(2), day.plusDays(9));
        var calendar = calendars.capture(calendarQuery);
        StoreCalendarSourcePolicy.prepare(calendarQuery, calendar, clock.instant());
        deadlines.add(calendar.validUntil());
        finalReads.add(
                () -> {
                    var after = calendars.capture(calendarQuery);
                    StoreCalendarSourcePolicy.prepare(calendarQuery, calendar, clock.instant());
                    StoreCalendarSourcePolicy.prepare(calendarQuery, after, clock.instant());
                    require(
                            calendar.store().equals(after.store())
                                    && calendar.policies().equals(after.policies())
                                    && calendar.exceptions().equals(after.exceptions()));
                    deadlines.add(after.validUntil());
                });
        var budget = SelectionFeasibility.Budget.catalog();
        var sections = new ArrayList<Section>();
        var offers = new ArrayList<ChannelCatalogDetails.Offer>();
        var windows = new ArrayList<CatalogAvailability.SourceWindow>();
        var taxes = new LinkedHashMap<String, CatalogTaxBasis>();
        CatalogAvailability horizon = null;
        String currency = null;
        long bytes = 0;
        for (var source : command.sources()) {
            var query =
                    new MenuSourceContracts.Query(
                            command.entityNo(),
                            command.storeNo(),
                            source.sourceRef(),
                            source.selectionRevision(),
                            MenuSourceContracts.Kind.MENU,
                            source.sourceRef(),
                            null,
                            null,
                            MenuSourceContracts.Mode.CURRENT);
            var proof = menus.verifyMenuResource(query);
            MenuSourcePolicy.validateProof(
                    json, query, proof, clock.instant().atOffset(ZoneOffset.UTC));
            bytes += proof.publication().definitionSnapshot().length() * 3L;
            require(
                    bytes <= 4 * 1024 * 1024
                            && source.proof()
                                    .contentHash()
                                    .equals(proof.publication().publication().contentHash()));
            deadlines.add(source.proof().validUntil());
            deadlines.add(proof.validUntil().toInstant());
            var body =
                    json.readValue(
                            proof.publication().definitionSnapshot(),
                            MenuPublicationSnapshot.class);
            var bindings =
                    body.menu().channelBindings().stream()
                            .filter(
                                    b ->
                                            "ACTIVE".equals(b.status())
                                                    && authority
                                                            .provider()
                                                            .providerCode()
                                                            .equals(b.provider())
                                                    && authority
                                                            .account()
                                                            .externalAccountRef()
                                                            .equals(b.channelAccountRef())
                                                    && authority
                                                            .connection()
                                                            .externalStoreRef()
                                                            .equals(b.externalStoreRef()))
                            .toList();
            require(bindings.size() == 1);
            var contexts =
                    body.menu().definition().contexts().stream()
                            .filter(
                                    c ->
                                            bindings.getFirst()
                                                    .bindingNo()
                                                    .equals(c.channelBindingNo()))
                            .toList();
            require(contexts.size() == 1);
            var timed =
                    FoodCatalogAvailabilityPolicy.compile(
                            proof, calendarQuery, calendar, clock, json);
            if (horizon != null)
                require(
                        horizon.horizonStart().equals(timed.availability().horizonStart())
                                && horizon.horizonEnd().equals(timed.availability().horizonEnd()));
            horizon = timed.availability();
            windows.addAll(horizon.sources());
            deadlines.add(timed.validUntil());
            boolean originallyEmpty =
                    body.menu().definition().composition().categories().stream()
                            .flatMap(category -> category.items().stream())
                            .noneMatch(item -> Boolean.TRUE.equals(item.enabled()));
            if (originallyEmpty) {
                require(withdrawalEnabled && scopePrices.get() != null);
                var scope =
                        new CatalogPriceSourcePort.MarketScope(
                                command.entityNo(), command.storeNo());
                var emptyScope = scopePrices.get().scope(scope);
                CatalogPriceSourceFacts.validateScopeFacts(scope, emptyScope, clock.instant());
                require(currency == null || currency.equals(emptyScope.market().currency()));
                currency = emptyScope.market().currency();
                deadlines.add(emptyScope.validUntil());
                finalReads.add(
                        () -> {
                            var after = menus.verifyMenuResource(query);
                            MenuSourcePolicy.validateProof(
                                    json, query, after, clock.instant().atOffset(ZoneOffset.UTC));
                            require(proof.publication().equals(after.publication()));
                            deadlines.add(after.validUntil().toInstant());
                        });
                continue;
            }
            var priceQuery =
                    new FoodCatalogPricePolicyContracts.Query(
                            new FoodCatalogBindingContracts.Query(
                                    query, authorityQuery, bindings.getFirst().bindingNo()),
                            contexts.getFirst().pricePolicyRef());
            var policy = prices.capture(priceQuery);
            FoodCatalogPricePolicy.validate(json, priceQuery, policy, clock.instant());
            require(policy.bindingFacts().menuProof().publication().equals(proof.publication()));
            var selection = MenuSelectionFeasibilityPolicy.prove(proof, clock, json, budget);
            require(selection.proven());
            deadlines.add(selection.validUntil());
            deadlines.add(policy.validUntil());
            var actualCurrency = policy.prices().productFacts().market().currency();
            require(currency == null || currency.equals(actualCurrency));
            currency = actualCurrency;

            var labels =
                    labels(
                            command,
                            body,
                            policy.prices().productFacts().market().market(),
                            finalReads,
                            deadlines);
            var images = images(command, body, finalReads, deadlines);
            var projection = FoodCatalogAssembler.assemble(policy, images, labels, json);
            sections.addAll(projection.sections());
            offers.addAll(projection.offers());
            require(offers.size() <= 10000 && sections.size() <= 500);
            if (!projection.offers().isEmpty())
                taxes.put(projection.taxBasis().taxBasisRef(), projection.taxBasis());
            finalReads.add(
                    () -> {
                        var after = prices.capture(priceQuery);
                        FoodCatalogPricePolicy.validate(json, priceQuery, policy, clock.instant());
                        FoodCatalogPricePolicy.validate(json, priceQuery, after, clock.instant());
                        require(
                                policy.bindingFacts()
                                                .menuProof()
                                                .publication()
                                                .equals(
                                                        after.bindingFacts()
                                                                .menuProof()
                                                                .publication())
                                        && policy.prices().prices().equals(after.prices().prices())
                                        && policy.prices()
                                                .productFacts()
                                                .market()
                                                .equals(after.prices().productFacts().market())
                                        && policy.prices()
                                                .productFacts()
                                                .tax()
                                                .equals(after.prices().productFacts().tax()));
                        deadlines.add(after.validUntil());
                    });
        }
        finalReads.forEach(Runnable::run);
        var latestAuthority = authorities.capture(authorityQuery);
        ChannelCatalogAuthorityPolicy.validate(authorityQuery, authority, clock.instant());
        ChannelCatalogAuthorityPolicy.validate(authorityQuery, latestAuthority, clock.instant());
        require(
                authority.provider().equals(latestAuthority.provider())
                        && authority.profile().equals(latestAuthority.profile())
                        && authority.account().equals(latestAuthority.account())
                        && authority.connection().equals(latestAuthority.connection())
                        && authority.grant().equals(latestAuthority.grant()));
        deadlines.add(latestAuthority.validUntil());
        var until = deadlines.stream().min(Instant::compareTo).orElseThrow();
        require(until.isAfter(clock.instant()));
        var availability =
                new CatalogAvailability(horizon.horizonStart(), horizon.horizonEnd(), windows);
        var document =
                new Document(
                        Owner.FOOD,
                        currency,
                        List.copyOf(command.sources()),
                        List.of(),
                        List.of(),
                        List.of(),
                        until,
                        PricingSnapshotHash.of(List.of(command, availability, until, started)),
                        3,
                        new ChannelCatalogDetails(sections, offers, List.of()),
                        availability,
                        List.copyOf(taxes.values()));
        if (withdrawalEnabled)
            return CatalogReplacementCapture.enrich(
                    command,
                    document,
                    authorities,
                    scopePrices.get(),
                    withdrawals.get(),
                    json,
                    clock);
        return document;
    }

    private Map<String, String> labels(
            CaptureCommand command,
            MenuPublicationSnapshot body,
            String market,
            List<Runnable> reads,
            List<Instant> deadlines) {
        var labels = new HashMap<String, String>();
        for (String number :
                body.skuFacts().stream().map(s -> s.goodsNo()).distinct().sorted().toList()) {
            var q =
                    new CatalogMarketContracts.GoodsQuery(
                            command.entityNo(), command.storeNo(), number, market);
            var f = goods.getCatalogGoods(q);
            CatalogMarketFacts.validate(q, f, clock.instant());
            for (var original : body.skuFacts())
                if (number.equals(original.goodsNo())) {
                    var sku =
                            f.goods().skus().stream()
                                    .filter(s -> original.skuNo().equals(s.skuNo()))
                                    .findFirst()
                                    .orElseThrow();
                    require(sku.version() == original.version());
                    labels.put(
                            sku.skuNo(),
                            sku.spec() == null || sku.spec().isBlank()
                                    ? f.goods().title()
                                    : sku.spec());
                }
            deadlines.add(f.validUntil());
            reads.add(
                    () -> {
                        var after = goods.getCatalogGoods(q);
                        CatalogMarketFacts.validate(q, f, clock.instant());
                        CatalogMarketFacts.validate(q, after, clock.instant());
                        require(
                                f.goods().equals(after.goods())
                                        && f.marketFacts()
                                                .market()
                                                .equals(after.marketFacts().market()));
                        deadlines.add(after.validUntil());
                    });
        }
        return labels;
    }

    private Map<String, List<Image>> images(
            CaptureCommand command,
            MenuPublicationSnapshot body,
            List<Runnable> reads,
            List<Instant> deadlines) {
        var result = new HashMap<String, List<Image>>();
        for (var dish : body.menu().dishes()) {
            var keys = new ArrayList<>(dish.presentation().imageRefs());
            if (keys.isEmpty()) {
                var original =
                        body.goodsFacts().stream()
                                .filter(g -> dish.goodsNo().equals(g.goodsNo()))
                                .findFirst()
                                .orElseThrow();
                var q =
                        new CatalogImageContracts.Query(
                                command.entityNo(),
                                command.storeNo(),
                                List.of(
                                        new CatalogImageContracts.Expectation(
                                                dish.goodsNo(),
                                                original.cover() == null
                                                                || original.cover().isEmpty()
                                                        ? "NONE"
                                                        : "REFERENCE",
                                                original.cover() == null
                                                                || original.cover().isEmpty()
                                                        ? null
                                                        : original.cover())));
                var f = goods.resolveCatalogImages(q);
                CatalogImageFacts.validate(q, f, clock.instant());
                if (f.items().getFirst().assetKey() != null)
                    keys.add(f.items().getFirst().assetKey());
                deadlines.add(f.validUntil());
                reads.add(
                        () -> {
                            var after = goods.resolveCatalogImages(q);
                            CatalogImageFacts.validate(q, f, clock.instant());
                            CatalogImageFacts.validate(q, after, clock.instant());
                            var a = f.items().getFirst();
                            var b = after.items().getFirst();
                            require(
                                    a.coverKind().equals(b.coverKind())
                                            && a.originalRefDigest().equals(b.originalRefDigest())
                                            && Objects.equals(a.assetKey(), b.assetKey())
                                            && a.goodsVersion().equals(b.goodsVersion())
                                            && Objects.equals(
                                                    a.assetIdentityHash(), b.assetIdentityHash()));
                            deadlines.add(after.validUntil());
                        });
            }
            var projected = new ArrayList<Image>();
            if (!keys.isEmpty()) {
                var q =
                        new MediaPublicSourcePort.Request(
                                command.entityNo(), command.storeNo(), keys);
                var f = media.capture(q);
                MediaPublicSourceFacts.validate(q, f, clock.instant());
                f.assets()
                        .forEach(
                                a ->
                                        projected.add(
                                                new Image(
                                                        a.assetKey(),
                                                        a.publicUrl(),
                                                        f.receiptNo(),
                                                        a.sourceRevision())));
                deadlines.add(f.validUntil());
                reads.add(
                        () -> {
                            var after = media.capture(q);
                            MediaPublicSourceFacts.validate(q, f, clock.instant());
                            MediaPublicSourceFacts.validate(q, after, clock.instant());
                            require(f.assets().equals(after.assets()));
                            deadlines.add(after.validUntil());
                        });
            }
            result.put(dish.goodsNo(), List.copyOf(projected));
        }
        return result;
    }

    private void validateCommand(CaptureCommand c) {
        require(
                c != null
                        && c.owner() == Owner.FOOD
                        && key(c.entityNo())
                        && key(c.storeNo())
                        && key(c.connectionNo())
                        && key(c.connectionRevisionNo())
                        && c.sources() != null
                        && (!c.sources().isEmpty()
                                || withdrawalEnabled
                                        && c.withdrawals() != null
                                        && !c.withdrawals().isEmpty())
                        && c.sources().size() <= 100);
        CatalogSourceTransportPolicy.validateCommand(c, clock);
        var seen = new HashSet<String>();
        for (var source : c.sources())
            require(
                    source != null
                            && source.owner() == Owner.FOOD
                            && key(source.sourceRef())
                            && key(source.selectionRevision())
                            && seen.add(source.sourceRef())
                            && source.priority() >= 0
                            && source.priority() <= 10000
                            && source.proof() != null
                            && source.proof().status() == Status.VALID
                            && source.sourceRef().equals(source.proof().canonicalRef())
                            && source.selectionRevision().equals(source.proof().canonicalRevision())
                            && source.proof().canonicalSalesOfferRef() == null
                            && source.proof().validUntil() != null
                            && source.proof().validUntil().isAfter(clock.instant()));
    }

    private static boolean key(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 32
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }
}
