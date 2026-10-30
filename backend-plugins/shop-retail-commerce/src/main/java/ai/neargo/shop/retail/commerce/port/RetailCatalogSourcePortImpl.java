package ai.neargo.shop.retail.commerce.port;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.commerce.policy.CatalogReplacementCapture;
import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.mapper.RetailSelectionMapper;
import ai.neargo.shop.spi.commerce.CatalogTaxBasis;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
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

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * Retail projection reuses original products, prices/tax, media and an explicit original calendar
 * owner.
 */
public final class RetailCatalogSourcePortImpl implements ChannelCatalogSourcePort {
    private final RetailSelectionMapper selections;
    private final RetailSourceValidationProviderImpl validation;
    private final ChannelCatalogAuthorityService authorities;
    private final GoodsCatalogQueryPort products;
    private final CatalogPriceSourcePort prices;
    private final MediaPublicSourcePort media;
    private final RetailCatalogAvailabilityPort calendars;
    private final ObjectMapper json;
    private final Clock clock;
    private boolean withdrawalEnabled;
    private Supplier<ChannelSourceWithdrawalProvider> withdrawals = () -> null;

    public void withdrawalSupport(
            boolean enabled, Supplier<ChannelSourceWithdrawalProvider> owners) {
        withdrawalEnabled = enabled;
        withdrawals = owners;
    }

    public RetailCatalogSourcePortImpl(
            RetailSelectionMapper selections,
            RetailSourceValidationProviderImpl validation,
            ChannelCatalogAuthorityService authorities,
            GoodsCatalogQueryPort products,
            CatalogPriceSourcePort prices,
            MediaPublicSourcePort media,
            RetailCatalogAvailabilityPort calendars,
            ObjectMapper json,
            Clock clock) {
        this.selections = selections;
        this.validation = validation;
        this.authorities = authorities;
        this.products = products;
        this.prices = prices;
        this.media = media;
        this.calendars = calendars;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Owner owner() {
        return Owner.RETAIL;
    }

    @Override
    public Document capture(CaptureCommand c) {
        CatalogSourceTransportPolicy.validateCommand(c, clock);
        RetailSourceValidationProviderImpl.outsideTransaction();
        require(c.owner() == Owner.RETAIL);
        require(c.withdrawals() == null || c.withdrawals().isEmpty() || withdrawalEnabled);
        if (c.sources().isEmpty()) {
            require(withdrawalEnabled);
            return CatalogReplacementCapture.enrich(
                    c, null, authorities, prices, withdrawals.get(), json, clock);
        }
        var deadlines = new ArrayList<Instant>();
        deadlines.add(clock.instant().plusSeconds(30));
        var aq =
                new ChannelCatalogAuthorityContracts.Query(
                        c.entityNo(), c.storeNo(), c.connectionNo(), c.connectionRevisionNo());
        var authority = authorities.capture(aq);
        ChannelCatalogAuthorityPolicy.validate(aq, authority, clock.instant());
        require("RETAIL".equals(authority.connection().sourceOwner()));
        deadlines.add(authority.validUntil());
        var selected = new ArrayList<RetailSelection>();
        var sources = new ArrayList<Source>();
        for (var source : c.sources()) {
            var q =
                    new Source(
                            c.entityNo(),
                            c.storeNo(),
                            Owner.RETAIL,
                            Kind.SELECTION,
                            source.sourceRef(),
                            source.selectionRevision());
            var proof = validation.validate(q);
            CatalogSourceTransportPolicy.validateProof(q, proof, clock);
            require(
                    proof.status() == Status.VALID
                            && proof.contentHash().equals(source.proof().contentHash()));
            var selection =
                    selections.revision(
                            c.entityNo(),
                            c.storeNo(),
                            source.sourceRef(),
                            source.selectionRevision());
            require(
                    selection != null
                            && c.connectionNo().equals(selection.connectionNo())
                            && c.connectionRevisionNo().equals(selection.connectionRevisionNo()));
            selected.add(selection);
            sources.add(q);
            deadlines.add(proof.validUntil());
            deadlines.add(source.proof().validUntil());
        }
        var skuNos =
                selected.stream()
                        .flatMap(s -> s.entries().stream())
                        .flatMap(e -> e.skuNos().stream())
                        .distinct()
                        .toList();
        var request = new CatalogPriceSourcePort.Request(c.entityNo(), c.storeNo(), skuNos);
        var priceFacts = prices.capture(request);
        CatalogPriceSourceFacts.validate(request, priceFacts, clock.instant());
        require(authority.profile().market().equals(priceFacts.market().market()));
        deadlines.add(priceFacts.validUntil());
        var metadata = new LinkedHashMap<String, GoodsCatalogQueryPort.GoodsFact>();
        for (var selection : selected)
            for (var entry : selection.entries())
                if (!metadata.containsKey(entry.goodsNo())) {
                    var q =
                            new CatalogMarketContracts.GoodsQuery(
                                    c.entityNo(),
                                    c.storeNo(),
                                    entry.goodsNo(),
                                    priceFacts.market().market());
                    var facts = products.getCatalogGoods(q);
                    CatalogMarketFacts.validate(q, facts, clock.instant());
                    metadata.put(entry.goodsNo(), facts.goods());
                    deadlines.add(facts.validUntil());
                }
        var imageQueries = new ArrayList<CatalogImageContracts.Query>();
        var imageProofs = new ArrayList<CatalogImageContracts.Facts>();
        var expectations =
                metadata.values().stream()
                        .map(
                                g ->
                                        new CatalogImageContracts.Expectation(
                                                g.goodsNo(),
                                                g.cover() == null || g.cover().isEmpty()
                                                        ? "NONE"
                                                        : "REFERENCE",
                                                g.cover() == null || g.cover().isEmpty()
                                                        ? null
                                                        : g.cover()))
                        .toList();
        for (int offset = 0; offset < expectations.size(); offset += 20) {
            var query =
                    new CatalogImageContracts.Query(
                            c.entityNo(),
                            c.storeNo(),
                            expectations.subList(
                                    offset, Math.min(offset + 20, expectations.size())));
            var facts = products.resolveCatalogImages(query);
            CatalogImageFacts.validate(query, facts, clock.instant());
            imageQueries.add(query);
            imageProofs.add(facts);
            deadlines.add(facts.validUntil());
        }
        var publicImages = new LinkedHashMap<String, Image>();
        for (var item : imageProofs.stream().flatMap(f -> f.items().stream()).toList()) {
            require(metadata.get(item.goodsNo()).version() == item.goodsVersion());
            if ("NONE".equals(item.coverKind())) continue;
            // Per asset original requests avoid expanding the media owner's twenty-asset batch
            // contract.
            var mq =
                    new MediaPublicSourcePort.Request(
                            c.entityNo(), c.storeNo(), List.of(item.assetKey()));
            var mf = media.capture(mq);
            MediaPublicSourceFacts.validate(mq, mf, clock.instant());
            deadlines.add(mf.validUntil());
            var asset = mf.assets().getFirst();
            publicImages.put(
                    item.goodsNo(),
                    new Image(
                            asset.assetKey(),
                            asset.publicUrl(),
                            mf.receiptNo(),
                            asset.sourceRevision()));
        }
        var calendar = calendars.capture(c.entityNo(), c.storeNo(), List.copyOf(selected));
        validateCalendar(c, calendar);
        deadlines.add(calendar.validUntil());
        String taxRef =
                "TAX:"
                        + PricingSnapshotHash.of(
                                List.of(
                                        c.entityNo(),
                                        c.storeNo(),
                                        priceFacts.market(),
                                        priceFacts.tax()));
        var tax =
                new CatalogTaxBasis(
                        taxRef, c.entityNo(), c.storeNo(), priceFacts.market(), priceFacts.tax());
        var sections = new ArrayList<Section>();
        var offers = new ArrayList<ChannelCatalogDetails.Offer>();
        for (var selection : selected)
            for (var entry : selection.entries()) {
                var goods = metadata.get(entry.goodsNo());
                var variants = new ArrayList<ChannelCatalogDetails.Variant>();
                for (var sku : entry.skuNos()) {
                    var originalSku =
                            goods.skus().stream()
                                    .filter(s -> s.skuNo().equals(sku))
                                    .findFirst()
                                    .orElseThrow();
                    var price =
                            priceFacts.prices().stream()
                                    .filter(p -> p.skuNo().equals(sku))
                                    .findFirst()
                                    .orElseThrow();
                    require(
                            "KNOWN".equals(price.status())
                                    && entry.goodsNo().equals(price.goodsNo())
                                    && price.versions().goods() == goods.version()
                                    && price.versions().sku() == originalSku.version());
                    variants.add(
                            new ChannelCatalogDetails.Variant(
                                    sku,
                                    originalSku.spec() == null || originalSku.spec().isBlank()
                                            ? goods.title()
                                            : originalSku.spec(),
                                    new ChannelCatalogDetails.UnitPrice(
                                            price.amountMinor(),
                                            priceFacts.market().currency(),
                                            sku,
                                            price.sourceRevision(),
                                            taxRef,
                                            priceFacts.receiptNo()),
                                    new ChannelCatalogDetails.Inventory("UNKNOWN", null, null),
                                    List.of(),
                                    List.of()));
                }
                // Presentation grouping uses the original goods title and makes no category-source
                // claim.
                String sectionKey = key(selection.selectionNo(), entry.goodsNo());
                sections.add(new Section(sectionKey, goods.title(), sections.size()));
                var image = publicImages.get(entry.goodsNo());
                offers.add(
                        new ChannelCatalogDetails.Offer(
                                key(selection.selectionNo(), entry.goodsNo()),
                                selection.selectionNo(),
                                selection.revisionNo(),
                                entry.goodsNo(),
                                goods.title(),
                                sectionKey,
                                variants,
                                image == null ? List.of() : List.of(image),
                                entry.goodsNo()));
            }
        var latestPrice = prices.capture(request);
        CatalogPriceSourceFacts.validate(request, latestPrice, clock.instant());
        require(
                priceFacts.market().equals(latestPrice.market())
                        && priceFacts.tax().equals(latestPrice.tax())
                        && priceFacts.prices().equals(latestPrice.prices()));
        deadlines.add(latestPrice.validUntil());
        for (var g : metadata.values()) {
            var q =
                    new CatalogMarketContracts.GoodsQuery(
                            c.entityNo(), c.storeNo(), g.goodsNo(), priceFacts.market().market());
            var after = products.getCatalogGoods(q);
            CatalogMarketFacts.validate(q, after, clock.instant());
            require(g.equals(after.goods()));
            deadlines.add(after.validUntil());
        }
        for (var source : sources) {
            var after = validation.validate(source);
            CatalogSourceTransportPolicy.validateProof(source, after, clock);
            require(
                    after.status() == Status.VALID
                            && selected.stream()
                                    .anyMatch(
                                            s ->
                                                    s.selectionNo().equals(source.sourceRef())
                                                            && s.contentHash()
                                                                    .equals(after.contentHash())));
            deadlines.add(after.validUntil());
        }
        for (int index = 0; index < imageQueries.size(); index++) {
            var query = imageQueries.get(index);
            var afterImages = products.resolveCatalogImages(query);
            CatalogImageFacts.validate(query, afterImages, clock.instant());
            require(stableImages(imageProofs.get(index)).equals(stableImages(afterImages)));
            deadlines.add(afterImages.validUntil());
        }
        for (var image : publicImages.values()) {
            var mq =
                    new MediaPublicSourcePort.Request(
                            c.entityNo(), c.storeNo(), List.of(image.assetNo()));
            var mf = media.capture(mq);
            MediaPublicSourceFacts.validate(mq, mf, clock.instant());
            var asset = mf.assets().getFirst();
            require(
                    image.url().equals(asset.publicUrl())
                            && image.sourceRevision().equals(asset.sourceRevision()));
            deadlines.add(mf.validUntil());
        }
        var afterCalendar = calendars.capture(c.entityNo(), c.storeNo(), List.copyOf(selected));
        validateCalendar(c, afterCalendar);
        require(
                calendar.sourceRevision().equals(afterCalendar.sourceRevision())
                        && calendar.availability().equals(afterCalendar.availability()));
        deadlines.add(afterCalendar.validUntil());
        var afterAuthority = authorities.capture(aq);
        ChannelCatalogAuthorityPolicy.validate(aq, afterAuthority, clock.instant());
        require(
                authority.connection().equals(afterAuthority.connection())
                        && authority.grant().equals(afterAuthority.grant())
                        && authority.profile().equals(afterAuthority.profile())
                        && authority.account().equals(afterAuthority.account())
                        && authority.provider().equals(afterAuthority.provider()));
        deadlines.add(afterAuthority.validUntil());
        var until = deadlines.stream().min(Instant::compareTo).orElseThrow();
        require(until.isAfter(clock.instant()));
        var details = new ChannelCatalogDetails(sections, offers, List.of());
        var document =
                new Document(
                        Owner.RETAIL,
                        priceFacts.market().currency(),
                        c.sources(),
                        List.of(),
                        List.of(),
                        List.of(),
                        until,
                        PricingSnapshotHash.of(
                                List.of(c, details, calendar.sourceRevision(), until)),
                        3,
                        details,
                        calendar.availability(),
                        List.of(tax));
        if (withdrawalEnabled)
            return CatalogReplacementCapture.enrich(
                    c, document, authorities, prices, withdrawals.get(), json, clock);
        return CatalogSourceTransportPolicy.validateDocument(c, document, json, clock);
    }

    private static List<List<Object>> stableImages(CatalogImageContracts.Facts facts) {
        return facts.items().stream()
                .map(
                        i ->
                                Arrays.<Object>asList(
                                        i.goodsNo(),
                                        i.coverKind(),
                                        i.originalRefDigest(),
                                        i.assetKey(),
                                        i.goodsVersion(),
                                        i.assetIdentityHash()))
                .toList();
    }

    private void validateCalendar(CaptureCommand c, RetailCatalogAvailabilityPort.Facts f) {
        require(
                f != null
                        && c.entityNo().equals(f.entityNo())
                        && c.storeNo().equals(f.storeNo())
                        && f.availability() != null
                        && f.sourceRevision() != null
                        && f.sourceRevision().matches("[a-f0-9]{64}")
                        && f.receiptNo() != null
                        && !f.receiptNo().isBlank()
                        && f.verifiedAt() != null
                        && !f.verifiedAt().isAfter(clock.instant())
                        && f.validUntil() != null
                        && f.validUntil().isAfter(clock.instant())
                        && !f.validUntil().isAfter(f.verifiedAt().plusSeconds(30)));
    }

    private static String key(String a, String b) {
        return a.length() + ":" + a + b.length() + ":" + b;
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("Original retail catalog evidence mismatch");
    }
}
