package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.commerce.policy.CatalogDocumentPolicy;
import ai.neargo.shop.commerce.policy.CatalogTaxBasisPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderEconomicPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderSelectionPolicy;
import ai.neargo.shop.commerce.policy.ChannelOrderSourceEvidencePolicy;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.SourcePriceMath;
import ai.neargo.shop.common.pricing.SourceQuantityUnits;
import ai.neargo.shop.food.dto.CartBundleChoiceInput;
import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Component;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Definition;
import ai.neargo.shop.food.dto.DiningChannelSourceFacts.Key;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.RecipePublicationDocuments;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.spi.DiningChannelSourceFactsPort;
import ai.neargo.shop.spi.commerce.ChannelCatalogDetails;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.Document;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Channel;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Collection;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Delivery;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Fulfillment;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.InvoiceLine;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Totals;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads actual CIM receipts and original FOOD publications before native FOOD persistence assigns
 * identities.
 */
public final class FoodChannelSourceFactsPortImpl implements DiningChannelSourceFactsPort {
    private final ChannelOrderSourceEvidencePort evidence;
    private final ConfigRevisionService revisions;
    private final MaterialCatalogQueryPort materials;
    private final ObjectMapper json;
    private final Clock clock;

    public FoodChannelSourceFactsPortImpl(
            ChannelOrderSourceEvidencePort evidence,
            ConfigRevisionService revisions,
            MaterialCatalogQueryPort materials,
            ObjectMapper json,
            Clock clock) {
        this.evidence = evidence;
        this.revisions = revisions;
        this.materials = materials;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public DiningChannelSourceFacts read(Key key) {
        var query = query(key);
        var read = ChannelOrderSourceEvidencePolicy.validate(query, evidence.read(query), clock);
        var publication = evidence.publishedCatalog(query);
        require(
                publication != null
                        && query.equals(publication.scope())
                        && publication.document() != null
                        && publication.document().owner() == Owner.FOOD
                        && text(publication.originalPublicationReceiptRef())
                        && text(publication.runNo())
                        && text(publication.snapshotNo())
                        && text(publication.operationNo())
                        && text(publication.attemptRef()));
        var command = read.original();
        var original = command.original().document();
        var catalog = publication.document();
        var routing = read.routing();
        require(command.original().observedAt() != null);
        ChannelOrderPolicy.validate(
                new ChannelOrderGateway.Query(
                        key.entityNo(),
                        key.storeNo(),
                        routing.connectionNo(),
                        routing.profileNo(),
                        original.externalAccountRef(),
                        original.externalStoreRef(),
                        original.externalOrderRef(),
                        null,
                        null,
                        null),
                command.original(),
                Clock.fixed(command.original().observedAt(), ZoneOffset.UTC));
        require(
                original.catalogHash().equals(publication.catalogHash())
                        && original.catalogHash().equals(CatalogDocumentPolicy.hash(catalog, json))
                        && original.currency().equals(catalog.currency()));
        CatalogTaxBasisPolicy.validate(catalog);
        CatalogTaxBasisPolicy.requireScope(catalog, key.entityNo(), key.storeNo());
        require(
                catalog.taxBases().stream()
                        .allMatch(t -> read.routing().market().equals(t.market().market())));
        var validated = ChannelOrderSelectionPolicy.validate(command, catalog);
        var definitions = new LinkedHashMap<String, Definition>();
        var deadline = read.current().validUntil();
        var lines = new ArrayList<DiningChannelSourceFacts.Line>();
        var invoices = new ArrayList<InvoiceLine>();
        for (var line : validated) {
            var menuQuery =
                    new MenuSourceContracts.Query(
                            key.entityNo(),
                            key.storeNo(),
                            line.menuRef(),
                            line.revisionRef(),
                            MenuSourceContracts.Kind.MENU,
                            line.menuRef(),
                            null,
                            null,
                            MenuSourceContracts.Mode.HISTORICAL);
            var proof = revisions.verifyMenuResource(menuQuery);
            MenuSourcePolicy.validateProof(
                    json, menuQuery, proof, clock.instant().atOffset(ZoneOffset.UTC));
            if (proof.validUntil().toInstant().isBefore(deadline))
                deadline = proof.validUntil().toInstant();
            var meta = proof.publication().publication();
            require(
                    catalog.sources().stream()
                                    .filter(
                                            s ->
                                                    line.menuRef().equals(s.sourceRef())
                                                            && line.revisionRef()
                                                                    .equals(s.selectionRevision())
                                                            && meta.contentHash()
                                                                    .equals(
                                                                            s.proof()
                                                                                    .contentHash()))
                                    .count()
                            == 1);
            add(
                    definitions,
                    new Definition(
                            meta.configKind(),
                            meta.configNo(),
                            meta.revisionNo(),
                            meta.revision(),
                            meta.contentHash(),
                            proof.publication().definitionSnapshot()));
            var menu =
                    json.readValue(
                            proof.publication().definitionSnapshot(),
                            MenuPublicationSnapshot.class);
            require(
                    menu.menu().channelBindings().stream()
                            .anyMatch(
                                    b ->
                                            "ACTIVE".equals(b.status())
                                                    && read.routing()
                                                            .providerCode()
                                                            .equals(b.provider())
                                                    && original.externalAccountRef()
                                                            .equals(b.channelAccountRef())
                                                    && original.externalStoreRef()
                                                            .equals(b.externalStoreRef())));
            for (var dependency : menu.menu().dependencies()) {
                var historical =
                        revisions.getRevision(
                                key.entityNo(), key.storeNo(), dependency.revisionNo());
                require(historical != null && historical.publication() != null);
                var d = historical.publication();
                require(
                        dependency.revisionNo().equals(d.revisionNo())
                                && dependency.configKind().equals(d.configKind())
                                && dependency.configNo().equals(d.configNo())
                                && dependency.revision() == d.revision()
                                && dependency.contentHash().equals(d.contentHash())
                                && d.contentHash()
                                        .equals(
                                                PublicationDocuments.hash(
                                                        json, historical.definitionSnapshot()))
                                && d.contentHash()
                                        .equals(
                                                PublicationDocuments.hash(
                                                        json, dependency.definitionSnapshot())));
                add(
                        definitions,
                        new Definition(
                                d.configKind(),
                                d.configNo(),
                                d.revisionNo(),
                                d.revision(),
                                d.contentHash(),
                                historical.definitionSnapshot()));
                loadNested(key, definitions.get(d.revisionNo()), definitions, new HashSet<>(), 0);
            }
            var invoice =
                    original.lines().stream()
                            .filter(l -> l.lineRef().equals(line.externalLineRef()))
                            .findFirst()
                            .orElseThrow();
            var offer =
                    catalog.details().offers().stream()
                            .filter(
                                    o ->
                                            line.menuRef().equals(o.sourceRef())
                                                    && line.originalOfferRef()
                                                            .equals(o.originalOfferRef())
                                                    && line.revisionRef()
                                                            .equals(o.selectionRevision()))
                            .findFirst()
                            .orElseThrow();
            var variant =
                    offer.variants().stream()
                            .filter(v -> line.skuRef().equals(v.skuRef()))
                            .findFirst()
                            .orElseThrow();
            var selections = new ArrayList<CartSelectionInput>();
            var bundles = new ArrayList<CartBundleChoiceInput>();
            var drafts = new ArrayList<Draft>();
            long mainWeight =
                    price(
                            line.publishedMainPrice(),
                            line.physicalQuantity(),
                            original.currency(),
                            catalog);
            for (var selection : line.selections()) {
                if ("OPTION".equals(selection.kind())) {
                    var group =
                            menu.menu().dependencies().stream()
                                    .filter(
                                            d ->
                                                    "OPTION_GROUP".equals(d.configKind())
                                                            && selection
                                                                    .groupRef()
                                                                    .equals(d.configNo()))
                                    .findFirst()
                                    .orElseThrow();
                    selections.add(
                            new CartSelectionInput(
                                    selection.groupRef(),
                                    selection.originalSourceKind(),
                                    selection.originalSourceRef(),
                                    selection.picksPerUnit(),
                                    json.readValue(
                                                    group.definitionSnapshot(),
                                                    PublicationSnapshot.class)
                                            .optionGroup()
                                            .revision()));
                } else
                    bundles.add(
                            new CartBundleChoiceInput(
                                    selection.choiceRef(), selection.picksPerUnit()));
                price(
                        selection.publishedPrice(),
                        selection.physicalQuantity(),
                        original.currency(),
                        catalog);
                if (selection.skuRef() == null) {
                    if ("MODIFIER".equals(selection.originalSourceKind())) {
                        var modifier =
                                menu.menu().dependencies().stream()
                                        .filter(
                                                d ->
                                                        "MODIFIER".equals(d.configKind())
                                                                && selection
                                                                        .originalSourceRef()
                                                                        .equals(d.configNo()))
                                        .findFirst()
                                        .orElseThrow();
                        var modifierData =
                                json.readValue(
                                                modifier.definitionSnapshot(),
                                                PublicationSnapshot.class)
                                        .modifier()
                                        .data();
                        require(modifierData.salesSkuNo() == null);
                    }
                    mainWeight = Math.addExact(mainWeight, selection.originalExtraMinor());
                    continue;
                }
                var sku =
                        menu.skuFacts().stream()
                                .filter(s -> selection.skuRef().equals(s.skuNo()))
                                .findFirst()
                                .orElseThrow();
                var child =
                        physical(
                                key,
                                menu,
                                definitions,
                                selection.skuRef(),
                                false,
                                null,
                                true,
                                selection.physicalQuantity());
                drafts.add(
                        new Draft(
                                selection.externalLineRef(),
                                line.externalLineRef(),
                                selection.kind(),
                                child.kind(),
                                sku.goodsNo(),
                                selection.skuRef(),
                                label(variant, selection),
                                null,
                                selection.physicalQuantity(),
                                child.unit(),
                                child.recipeRevision(),
                                selection,
                                selection.originalExtraMinor()));
            }
            var item =
                    menu.menu().definition().composition().categories().stream()
                            .flatMap(c -> c.items().stream())
                            .filter(i -> line.originalOfferRef().equals(i.menuItemNo()))
                            .findFirst()
                            .orElseThrow();
            var dish =
                    menu.menu().dishes().stream()
                            .filter(d -> line.goodsRef().equals(d.goodsNo()))
                            .findFirst()
                            .orElseThrow();
            var recipes =
                    item.skuRecipes().stream()
                            .filter(r -> line.skuRef().equals(r.skuNo()))
                            .toList();
            require(recipes.size() <= 1);
            var main =
                    physical(
                            key,
                            menu,
                            definitions,
                            line.skuRef(),
                            "BUNDLE".equals(dish.dishKind()),
                            recipes.isEmpty() ? null : recipes.getFirst().recipeNo(),
                            false,
                            line.physicalQuantity());
            require(SourceQuantityUnits.same(invoice.unit(), main.unit()));
            drafts.add(
                    0,
                    new Draft(
                            line.externalLineRef(),
                            null,
                            "MAIN",
                            main.kind(),
                            line.goodsRef(),
                            line.skuRef(),
                            offer.name(),
                            offer.images().isEmpty() ? null : offer.images().getFirst().url(),
                            line.physicalQuantity(),
                            main.unit(),
                            main.recipeRevision(),
                            null,
                            mainWeight));
            var amounts =
                    FoodOriginalInvoiceAllocation.allocate(
                            invoice,
                            drafts.stream().map(Draft::weight).toList(),
                            publication.originalPublicationReceiptRef(),
                            command.original().documentHash());
            var components = new ArrayList<Component>();
            for (int i = 0; i < drafts.size(); i++) {
                var d = drafts.get(i);
                var a = amounts.get(i);
                var s = d.selection();
                components.add(
                        new Component(
                                d.externalRef(),
                                d.externalRef(),
                                d.parentRef(),
                                d.externalKind(),
                                d.productionKind(),
                                d.goods(),
                                d.sku(),
                                d.title(),
                                d.cover(),
                                "MAIN".equals(d.externalKind()) ? variant.name() : d.title(),
                                d.quantity(),
                                d.unit(),
                                a.gross(),
                                a.discount(),
                                a.tax(),
                                a.net(),
                                a.basisRef(),
                                d.recipeRevision(),
                                s == null ? null : s.groupRef(),
                                s == null ? null : s.originalSourceKind(),
                                s == null ? null : s.originalSourceRef(),
                                s != null && "BUNDLE_MEMBER".equals(s.kind())
                                        ? s.choiceRef()
                                        : null));
            }
            lines.add(
                    new DiningChannelSourceFacts.Line(
                            line.externalLineRef(),
                            line.menuRef(),
                            line.originalOfferRef(),
                            line.revisionRef(),
                            line.goodsRef(),
                            line.skuRef(),
                            line.physicalQuantity(),
                            main.unit(),
                            invoice.note(),
                            selections,
                            bundles,
                            components));
            invoices.add(
                    new InvoiceLine(
                            invoice.lineRef(),
                            line.physicalQuantity(),
                            main.unit(),
                            invoice.grossExTaxMinor(),
                            invoice.merchantDiscountExTaxMinor(),
                            invoice.taxMinor(),
                            invoice.netMinor()));
        }
        var t = original.totals();
        var totals =
                new Totals(
                        sum(original, 0),
                        sum(original, 1),
                        sum(original, 2),
                        t.merchantChargesMinor(),
                        t.merchantReceivableMinor(),
                        t.platformSubsidyMinor(),
                        t.platformFeesMinor(),
                        t.deliveryFeesMinor(),
                        t.customerPayableMinor());
        var customer = original.customer();
        var route = read.routing();
        var packet =
                new DiningChannelSourceFacts.Original(
                        new Channel(
                                route.providerCode(),
                                route.profileNo(),
                                route.channelStoreKey(),
                                original.externalAccountRef(),
                                original.externalStoreRef(),
                                route.market()),
                        command.connectionNo(),
                        command.connectionRevisionNo(),
                        command.externalSnapshotNo(),
                        original.externalOrderRef(),
                        command.original().documentHash(),
                        ChannelOrderEconomicPolicy.fingerprint(original),
                        command.original().originalReceiptRef(),
                        original.currency(),
                        collection(original),
                        original.originalPaymentRef(),
                        fulfillment(original.fulfillment()),
                        new DiningChannelSourceFacts.PartyDescription(
                                customer == null ? null : customer.externalCustomerRef(),
                                customer == null ? null : customer.displayName()),
                        customer == null
                                ? null
                                : new Delivery(
                                        customer.displayName(),
                                        customer.phone(),
                                        customer.address(),
                                        null),
                        original.orderedAt(),
                        original.requestedAt(),
                        invoices,
                        totals,
                        lines,
                        List.copyOf(definitions.values()),
                        publication.catalogHash(),
                        publication.originalPublicationReceiptRef());
        require(deadline.isAfter(clock.instant()));
        return new DiningChannelSourceFacts(
                key,
                packet,
                current(read),
                read.current().observedAt(),
                deadline,
                read.current().originalReceiptRef());
    }

    @Override
    public CurrentEvidence current(Key key) {
        var query = query(key);
        var read = ChannelOrderSourceEvidencePolicy.validate(query, evidence.read(query), clock);
        return new CurrentEvidence(
                key,
                current(read),
                read.current().observedAt(),
                read.current().validUntil(),
                read.current().originalReceiptRef());
    }

    private ChannelOrderSourceEvidencePort.Query query(Key key) {
        require(key != null && !TransactionSynchronizationManager.isActualTransactionActive());
        var q =
                new ChannelOrderSourceEvidencePort.Query(
                        key.entityNo(),
                        key.storeNo(),
                        key.importNo(),
                        Owner.FOOD,
                        key.originalDocumentHash());
        ChannelOrderSourceEvidencePolicy.query(q);
        return q;
    }

    private DiningChannelSourceFacts.Current current(ChannelOrderSourceEvidencePort.Evidence read) {
        var s = read.current();
        var d = s.document();
        var c = collection(d);
        return new DiningChannelSourceFacts.Current(
                d.externalOrderRef(),
                s.documentHash(),
                ChannelOrderEconomicPolicy.fingerprint(d),
                DiningChannelSourceFacts.State.valueOf(d.state().name()),
                c,
                c == Collection.PLATFORM_COLLECTED ? d.totals().customerPayableMinor() : null,
                d.originalPaymentRef(),
                null,
                s.originalReceiptRef(),
                read.operationNo(),
                read.attemptRef());
    }

    private record Physical(String kind, String unit, String recipeRevision) {}

    private record Draft(
            String externalRef,
            String parentRef,
            String externalKind,
            String productionKind,
            String goods,
            String sku,
            String title,
            String cover,
            BigDecimal quantity,
            String unit,
            String recipeRevision,
            ChannelOrderSelectionPolicy.Selection selection,
            long weight) {}

    private Physical physical(
            Key key,
            MenuPublicationSnapshot menu,
            Map<String, Definition> definitions,
            String sku,
            boolean bundle,
            String recipeNo,
            boolean discoverRecipe,
            BigDecimal quantity) {
        if (bundle) {
            var dish =
                    menu.menu().dishes().stream()
                            .filter(
                                    d ->
                                            menu.skuFacts().stream()
                                                    .anyMatch(
                                                            s ->
                                                                    sku.equals(s.skuNo())
                                                                            && d.goodsNo()
                                                                                    .equals(
                                                                                            s
                                                                                                    .goodsNo())))
                            .findFirst()
                            .orElseThrow();
            return new Physical("BUNDLE_CONTAINER", dish.measurement().unit(), null);
        }
        var candidates =
                menu.menu().dependencies().stream()
                        .filter(d -> "RECIPE".equals(d.configKind()))
                        .filter(
                                d ->
                                        recipeNo != null
                                                ? recipeNo.equals(d.configNo())
                                                : discoverRecipe)
                        .filter(
                                d ->
                                        sku.equals(
                                                RecipePublicationDocuments.validate(
                                                                json,
                                                                d.configNo(),
                                                                d.definitionSnapshot())
                                                        .recipe()
                                                        .skuNo()))
                        .toList();
        require(candidates.size() <= 1 && (recipeNo == null || candidates.size() == 1));
        if (!candidates.isEmpty()) {
            var d = candidates.getFirst();
            require(definitions.containsKey(d.revisionNo()));
            var recipe =
                    RecipePublicationDocuments.validate(json, d.configNo(), d.definitionSnapshot());
            RecipePublicationDocuments.scope(recipe, key.entityNo(), key.storeNo());
            return new Physical("RECIPE_MATERIAL", recipe.recipe().yieldUnit(), d.revisionNo());
        }
        var found = materials.getSkuMaterials(key.entityNo(), key.storeNo(), List.of(sku));
        require(found != null && found.size() == 1 && sku.equals(found.getFirst().skuNo()));
        var material = found.getFirst().material();
        require(
                MaterialCatalogFacts.validFact(material)
                        && key.entityNo().equals(material.entityNo())
                        && (quantity.stripTrailingZeros().scale() <= 0
                                || Integer.valueOf(1).equals(material.weighed())));
        return new Physical("FINISHED_SKU", material.baseUom(), null);
    }

    private long price(
            ChannelCatalogDetails.UnitPrice price,
            BigDecimal quantity,
            String currency,
            Document catalog) {
        require(
                price != null
                        && price.amountMinor() != null
                        && price.amountMinor() >= 0
                        && currency.equals(price.currency())
                        && text(price.receiptNo())
                        && text(price.priceRevision()));
        var taxes =
                catalog.taxBases().stream()
                        .filter(t -> Objects.equals(price.taxBasisRef(), t.taxBasisRef()))
                        .toList();
        require(taxes.size() == 1 && taxes.getFirst().tax() != null);
        return SourcePriceMath.extend(new MoneyMinor(currency, price.amountMinor()), quantity)
                .minor();
    }

    private String label(
            ChannelCatalogDetails.Variant variant, ChannelOrderSelectionPolicy.Selection s) {
        if ("OPTION".equals(s.kind()))
            return variant.optionGroups().stream()
                    .filter(g -> s.groupRef().equals(g.groupRef()))
                    .flatMap(g -> g.choices().stream())
                    .filter(c -> s.choiceRef().equals(c.choiceRef()))
                    .map(ChannelCatalogDetails.Option::name)
                    .findFirst()
                    .orElseThrow();
        return variant.bundleGroups().stream()
                .filter(g -> s.groupRef().equals(g.groupRef()))
                .flatMap(g -> g.choices().stream())
                .filter(c -> s.choiceRef().equals(c.choiceRef()))
                .map(ChannelCatalogDetails.BundleMember::name)
                .findFirst()
                .orElseThrow();
    }

    private void add(Map<String, Definition> definitions, Definition value) {
        require(value.contentHash().equals(PublicationDocuments.hash(json, value.snapshot())));
        var prior = definitions.putIfAbsent(value.revisionNo(), value);
        require(prior == null || prior.equals(value));
    }

    private void loadNested(
            Key key,
            Definition definition,
            Map<String, Definition> definitions,
            Set<String> path,
            int depth) {
        require(depth <= 8 && definitions.size() <= 2000 && path.add(definition.revisionNo()));
        if ("MODIFIER".equals(definition.kind()) || "OPTION_GROUP".equals(definition.kind())) {
            var body = json.readValue(definition.snapshot(), PublicationSnapshot.class);
            for (var edge : body.dependencies()) {
                var value = revisions.getRevision(key.entityNo(), key.storeNo(), edge.revisionNo());
                require(value != null && value.publication() != null);
                var meta = value.publication();
                require(
                        edge.revisionNo().equals(meta.revisionNo())
                                && edge.configKind().equals(meta.configKind())
                                && edge.configNo().equals(meta.configNo())
                                && edge.revision() == meta.revision()
                                && edge.contentHash().equals(meta.contentHash()));
                var child =
                        new Definition(
                                meta.configKind(),
                                meta.configNo(),
                                meta.revisionNo(),
                                meta.revision(),
                                meta.contentHash(),
                                value.definitionSnapshot());
                add(definitions, child);
                loadNested(key, child, definitions, path, depth + 1);
            }
        }
        path.remove(definition.revisionNo());
    }

    private static Collection collection(ChannelOrderGateway.Document document) {
        return switch (document.paymentState()) {
            case PLATFORM_PAID -> Collection.PLATFORM_COLLECTED;
            case CASH_ON_DELIVERY -> Collection.CASH_ON_DELIVERY;
            case UNPAID -> Collection.UNPAID;
        };
    }

    private static Fulfillment fulfillment(ChannelOrderGateway.Fulfillment value) {
        return switch (value) {
            case INSTANT_DELIVERY -> Fulfillment.CHANNEL_DELIVERY;
            case PICKUP -> Fulfillment.STORE_PICKUP;
            case SHIPMENT -> Fulfillment.SHIPMENT;
            case VOUCHER ->
                    throw new IllegalStateException(
                            "FOOD physical source cannot import voucher fulfillment");
        };
    }

    private static long sum(ChannelOrderGateway.Document document, int field) {
        return document.lines().stream()
                .mapToLong(
                        l ->
                                switch (field) {
                                    case 0 -> l.grossExTaxMinor();
                                    case 1 -> l.merchantDiscountExTaxMinor();
                                    default -> l.taxMinor();
                                })
                .reduce(0, Math::addExact);
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean valid) {
        if (!valid)
            throw new IllegalStateException(
                    "Missing or inconsistent original FOOD source evidence");
    }
}
