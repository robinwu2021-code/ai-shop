package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.dto.MenuSourceContracts.Proof;
import ai.neargo.shop.food.dto.MenuSourceContracts.Query;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.dto.StationPublicationSnapshot;
import ai.neargo.shop.food.policy.MenuConflictPolicy;
import ai.neargo.shop.food.policy.MenuPublicationSource;
import ai.neargo.shop.food.policy.StationPublicationSource;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.StationPublicationDocuments;
import ai.neargo.shop.food.spi.MenuChannelAuthorityPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import java.util.function.Supplier;
import java.util.TreeMap;
import ai.neargo.shop.food.dto.BundleConfigVO;
import ai.neargo.shop.food.dto.BundlePublicationSnapshot;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.PublicationSnapshot.Dependency;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.RepublishConfigReq;
import ai.neargo.shop.food.dto.RecipePublicationSnapshot;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbMenuChannelBinding;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.entity.FnbModifier;
import ai.neargo.shop.food.entity.FnbModifierComponent;
import ai.neargo.shop.food.entity.FnbOptionGroup;
import ai.neargo.shop.food.entity.FnbOptionGroupItem;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuChannelBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.policy.BundlePublicationSource;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.OptionPublicationPolicy;
import ai.neargo.shop.food.policy.OptionSelectionPolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.RecipePublicationDocuments;
import ai.neargo.shop.food.policy.RecipePublicationSource;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.ConfigPublished;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Append-only publication facts are resolved by store/time, never by the editable entity-shared pointer. */
public class ConfigRevisionServiceImpl implements ConfigRevisionService {
    private final ConfigRevisionMapper revisions;
    private final ModifierMapper modifiers;
    private final ModifierComponentMapper components;
    private final OptionGroupMapper groups;
    private final OptionGroupItemMapper items;
    private final ActionLogMapper actions;
    private final IdempotencyService idem;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final GoodsCatalogQueryPort goods;
    private final SpecValueCatalogQueryPort specs;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    private final TransactionTemplate bundleWrite;
    private final BundlePublicationSource bundles;
    private final RecipePublicationSource recipes;
    private final MaterialCatalogQueryPort materials;
    private final MenuPublicationSource menus;
    private final StationPublicationSource stations;
    private final Supplier<MenuChannelAuthorityPort> channelAuthority;
    private final MenuChannelBindingMapper channelBindings;

    public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers,
            ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items,
            ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs) {
        this(revisions, modifiers, components, groups, items, actions, idem, publisher, json,
                manager, goods, specs, Clock.systemUTC());
    }

    public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers,
            ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items,
            ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs,
            Clock clock) {
        this(revisions, modifiers, components, groups, items, actions, idem, publisher, json,
                manager, goods, specs, clock, null);
    }

    public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers,
            ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items,
            ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs,
            Clock clock, BundlePublicationSource bundles) {
        this(revisions, modifiers, components, groups, items, actions, idem, publisher, json, manager,
                goods, specs, clock, bundles, null, null);
    }

    public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers,
            ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items,
            ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs,
            Clock clock, BundlePublicationSource bundles, RecipePublicationSource recipes,
            MaterialCatalogQueryPort materials) {
        this(revisions, modifiers, components, groups, items, actions, idem, publisher, json, manager,
                goods, specs, clock, bundles, recipes, materials, null, null, () -> null);
    }

    public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers,
            ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items,
            ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs,
            Clock clock, BundlePublicationSource bundles, RecipePublicationSource recipes,
            MaterialCatalogQueryPort materials, MenuPublicationSource menus, StationPublicationSource stations,
            Supplier<MenuChannelAuthorityPort> channelAuthority) {
        this(revisions, modifiers, components, groups, items, actions, idem, publisher, json, manager,
                goods, specs, clock, bundles, recipes, materials, menus, stations, channelAuthority, null);
    }

    public ConfigRevisionServiceImpl(ConfigRevisionMapper revisions, ModifierMapper modifiers,
            ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items,
            ActionLogMapper actions, IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort goods, SpecValueCatalogQueryPort specs,
            Clock clock, BundlePublicationSource bundles, RecipePublicationSource recipes,
            MaterialCatalogQueryPort materials, MenuPublicationSource menus, StationPublicationSource stations,
            Supplier<MenuChannelAuthorityPort> channelAuthority, MenuChannelBindingMapper channelBindings) {
        this.channelBindings = channelBindings;
        this.menus = menus; this.stations = stations; this.channelAuthority = channelAuthority;
        this.recipes = recipes; this.materials = materials;
        this.revisions = revisions; this.modifiers = modifiers; this.components = components;
        this.groups = groups; this.items = items; this.actions = actions; this.idem = idem;
        this.publisher = publisher; this.json = json; this.goods = goods; this.specs = specs; this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.bundles = bundles;
        bundleWrite = new TransactionTemplate(manager);
        bundleWrite.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    @Override
    public PublishConfigResultVO publishMenu(String entity, String store, String number, String actor,
            PublishConfigReq req, String key) {
        return publish(entity, store, "MENU", number, actor, req, key);
    }
    @Override
    public PublishConfigResultVO publishStation(String entity, String store, String number, String actor,
            PublishConfigReq req, String key) {
        return publish(entity, store, "STATION", number, actor, req, key);
    }

    @Override
    public PublishConfigResultVO publishRecipe(String entity, String store, String number, String actor,
            PublishConfigReq req, String key) {
        return publish(entity, store, "RECIPE", number, actor, req, key);
    }

    @Override
    public PublishConfigResultVO publishBundle(String entity, String store, String number, String actor,
            PublishConfigReq req, String key) {
        return publish(entity, store, "BUNDLE", number, actor, req, key);
    }

    @Override
    public PublishConfigResultVO publishModifier(String entity, String store, String number, String actor,
            PublishConfigReq req, String key) {
        return publish(entity, store, "MODIFIER", number, actor, req, key);
    }

    @Override
    public PublishConfigResultVO publishOptionGroup(String entity, String store, String number, String actor,
            PublishConfigReq req, String key) {
        return publish(entity, store, "OPTION_GROUP", number, actor, req, key);
    }

    private PublishConfigResultVO publish(String entity, String store, String kind, String number, String actor,
            PublishConfigReq req, String key) {
        scope(entity, store); identifier(number); identifier(actor);
        if (req == null || req.version() == null || req.version() < 0 || req.effectiveAt() == null
                || req.effectiveAt().getNano() % 1_000_000 != 0 || key == null || key.isBlank() || key.length() > 128
                || req.approvalRef() != null && req.approvalRef().length() > 64) throw bad();
        OffsetDateTime now = now();
        OffsetDateTime requested = req.effectiveAt().withOffsetSameInstant(ZoneOffset.UTC);
        if (requested.getYear() < 1000 || requested.getYear() > 9999) throw bad();
        OffsetDateTime effective = requested.isAfter(now) ? requested : now;
        String approval = req.approvalRef() == null || req.approvalRef().isBlank() ? null : req.approvalRef().trim();
        String endpoint = FoodCommandFingerprint.scope("PUBLISH_" + kind, entity, store);
        String fingerprint = FoodCommandFingerprint.hash(kind, number, req.version().toString(), requested.toString(),
                approval == null ? "" : approval);
        var completed = read.execute(tx -> idem.findCompleted(
                key, endpoint, actor, fingerprint, PublishConfigResultVO.class));
        if (completed.isPresent()) return completed.get();
        // Capture and all original remote reads finish before starting the local write transaction.
        var preliminary = read.execute(tx -> capture(entity, store, kind, number, effective, false));
        var fullGoods = new ArrayList<GoodsFact>();
        if (preliminary.menu() != null) {
            for (String goodsNo : menus.goodsNos(preliminary.menu())) {
                var fact = goods.getGoods(entity, store, goodsNo);
                if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                        || !goodsNo.equals(fact.goodsNo()) || fact.version() < 0 || fact.skus() == null
                        || fact.skus().isEmpty() || fact.skus().size() > 100) {
                    throw new IllegalStateException("Invalid menu original goods identity");
                }
                fullGoods.add(fact);
            }
        }
        var candidate = preliminary.menu() == null ? preliminary
                : read.execute(tx -> capture(entity, store, kind, number, effective, false, fullGoods));
        var requestedSkus = new TreeSet<>(candidate.skuNos());
        if (candidate.menu() != null) for (var item : MenuPublicationDocuments.items(candidate.menu())) {
            if ("ALL".equals(item.skuScope())) fullGoods.stream().filter(g -> g.goodsNo().equals(item.goodsNo()))
                    .forEach(g -> g.skus().forEach(sku -> requestedSkus.add(sku.skuNo())));
        }
        if (requestedSkus.size() > 1000) throw bad();
        var skuFacts = new ArrayList<SkuIdentityFact>();
        for (String sku : requestedSkus) {
            var fact = goods.getSku(entity, store, sku);
            if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                    || !sku.equals(fact.skuNo()) || !validKey(fact.goodsNo(), 32) || fact.version() < 0) {
                throw new IllegalStateException("Invalid original SKU identity");
            }
            skuFacts.add(fact);
        }
        List<SpecValueFact> specFacts = candidate.specNos().isEmpty() ? List.of()
                : specs.getValues(entity, candidate.specNos());
        validateSpecs(entity, candidate.specNos(), specFacts);
        List<MaterialFact> materialFacts = candidate.recipe() == null ? List.of()
                : materials.getMaterials(entity, candidate.recipe().recipe().lines().stream()
                        .map(line -> line.itemRef()).distinct().sorted().toList());
        if (candidate.recipe() != null) {
            recipes.validateAuthorities(entity, candidate.recipe(), skuFacts, materialFacts);
        }
        if (candidate.menu() != null) revalidateMenuAuthorities(entity, store, candidate.menu());
        try {
            var transaction = Set.of("BUNDLE", "RECIPE", "MENU", "STATION").contains(kind) ? bundleWrite : write;
            return transaction.execute(tx -> idem.execute(key, endpoint, actor, fingerprint,
                    PublishConfigResultVO.class, () -> {
                if (requested.isBefore(now.minusMinutes(5))) throw bad();
                var locked = capture(entity, store, kind, number, effective, true, fullGoods);
                if (locked.version() != req.version() || locked.version() == Long.MAX_VALUE
                        || !json.writeValueAsString(candidate).equals(json.writeValueAsString(locked))) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                boolean bundle = "BUNDLE".equals(kind);
                boolean recipe = "RECIPE".equals(kind);
                boolean menu = "MENU".equals(kind), station = "STATION".equals(kind);
                String document = menu ? PublicationDocuments.freeze(json, new MenuPublicationSnapshot(
                        4, kind, number, locked.version(), locked.menu(), skuFacts, fullGoods, approval))
                        : station ? PublicationDocuments.freeze(json, new StationPublicationSnapshot(
                                5, kind, number, locked.version(), entity, store, locked.station().definition(),
                                locked.station().routes().stream().map(r -> new StationPublicationSnapshot.Route(
                                        r.skuNo(), r.taskRole(), r.routeVersion())).toList(), skuFacts, approval))
                        : recipe ? PublicationDocuments.freeze(json,
                        recipes.document(locked.recipe(), skuFacts, materialFacts, approval))
                        : bundle ? PublicationDocuments.freeze(json,
                                new BundlePublicationSnapshot(
                        2, kind, number, locked.version(), locked.bundle(), skuFacts, approval))
                        : PublicationDocuments.freeze(json, new PublicationSnapshot(1, kind, number, locked.version(),
                        locked.modifier(), locked.optionGroup(), locked.dependencies(), skuFacts,
                        specFacts.stream().sorted(Comparator.comparing(SpecValueFact::valueNo)).toList(), approval));
                if (document.getBytes(StandardCharsets.UTF_8).length > 1_048_576) throw bad();
                if (recipe) RecipePublicationDocuments.scope(
                        RecipePublicationDocuments.validate(json, number, document), entity, store);
                PublicationDocuments.validate(json, kind, number, document);
                if (menu) checkMenuConflict(entity, store, number, document, effective);
                var history = revisions.findObject(entity, kind, number);
                long sequence = history.isEmpty() ? 1 : Math.addExact(history.getFirst().getRevision(), 1);
                var row = new FnbConfigRevision();
                row.setEntityNo(entity); row.setCreatedBy(actor); row.setRevisionNo(BizKey.next("FREV"));
                row.setConfigKind(kind); row.setConfigNo(number); row.setRevision(sequence);
                row.setContentSchema(menu ? 4 : station ? 5 : recipe ? 3 : bundle ? 2 : 1);
                row.setDefinitionSnapshot(document); row.setContentHash(hash(document));
                row.setEffectiveAt(effective.toLocalDateTime()); row.setScopeStoreNos(json.writeValueAsString(List.of(store)));
                row.setStatus(effective.isAfter(now) ? "SCHEDULED" : "EFFECTIVE");
                row.setPreviousRevisionNo(history.isEmpty() ? null : history.getFirst().getRevisionNo());
                if (revisions.append(row) != 1) throw new IllegalStateException("Missing publication append");
                if (menu) menus.advance(entity, store, number, req.version(), row);
                else if (station) stations.advance(entity, store, number, req.version(), row);
                else if (recipe) recipes.advance(entity, store, number, req.version(), row);
                else advance(entity, kind, number, req.version(), row);
                var result = new PublishConfigResultVO(view(row, now));
                var audit = new FnbActionLog();
                audit.setEntityNo(entity); audit.setStoreNo(store); audit.setActionNo(BizKey.next("FACT"));
                audit.setObjectType(kind); audit.setObjectNo(number); audit.setAction("PUBLISH_" + kind);
                audit.setOperatorNo(actor); audit.setRequestNo(FoodCommandFingerprint.hash(endpoint, key));
                audit.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(candidate)));
                audit.setAfterRef(row.getContentHash()); actions.insert(audit);
                publisher.publish(new ConfigPublished(entity, store, audit.getActionNo(), result.publication()),
                        new PublishOptions("FOOD_CONFIG:" + kind + ":" + number, 1));
                return result;
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException | ArithmeticException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public PublishConfigResultVO republishRevision(String entity, String store, String sourceNo, String actor,
            RepublishConfigReq req, String key) {
        scope(entity, store); identifier(sourceNo); identifier(actor);
        if (req == null || req.version() == null || req.version() < 0 || req.version() == Long.MAX_VALUE
                || req.effectiveAt() == null || req.effectiveAt().getNano() % 1_000_000 != 0
                || req.reason() == null || req.reason().isBlank() || req.reason().length() > 255
                || req.reason().codePoints().anyMatch(Character::isISOControl)
                || req.approvalRef() != null && (req.approvalRef().isBlank() || req.approvalRef().length() > 64)
                || key == null || key.isBlank() || key.length() > 128) throw bad();
        String auditReason = req.approvalRef() == null ? req.reason()
                : json.writeValueAsString(new RepublicationReason(req.reason(), req.approvalRef()));
        if (auditReason.length() > 255) throw bad();
        String endpoint = FoodCommandFingerprint.scope("REPUBLISH_CONFIG", entity, store);
        String fingerprint = FoodCommandFingerprint.hash(sourceNo, json.writeValueAsString(req));
        var completed = read.execute(tx -> idem.findCompleted(key, endpoint, actor, fingerprint,
                PublishConfigResultVO.class));
        if (completed.isPresent()) return completed.get();
        var source = read.execute(tx -> {
            var row = revisions.findRevision(entity, sourceNo);
            if (row == null || !inStore(row, store)) throw BizException.of(ErrorCode.NOT_FOUND);
            if ("CANCELLED".equals(row.getStatus())) throw BizException.of(ErrorCode.CONFLICT);
            validateFrozen(row);
            return row;
        });
        var now = now(); var requested = req.effectiveAt().withOffsetSameInstant(ZoneOffset.UTC);
        if (requested.getYear() < 1000 || requested.getYear() > 9999
                || requested.isBefore(now.minusMinutes(5))) throw bad();
        var effective = requested.isAfter(now) ? requested : now;
        read.execute(tx -> currentVersion(entity, store, source.getConfigKind(), source.getConfigNo(), false));
        revalidateHistorical(entity, store, source, new HashSet<>());
        try {
            return bundleWrite.execute(tx -> idem.execute(key, endpoint, actor, fingerprint,
                    PublishConfigResultVO.class, () -> {
                var original = revisions.findRevision(entity, sourceNo);
                if (original == null || !source.getContentHash().equals(original.getContentHash())
                        || !source.getDefinitionSnapshot().equals(original.getDefinitionSnapshot())
                        || !source.getStatus().equals(original.getStatus())
                        || currentVersion(entity, store, source.getConfigKind(), source.getConfigNo(), true)
                                != req.version()) throw BizException.of(ErrorCode.CONFLICT);
                if ("MENU".equals(source.getConfigKind())) {
                    revalidateHistoricalMenuLocal(entity, store, json.readValue(source.getDefinitionSnapshot(),
                            MenuPublicationSnapshot.class).menu(), true);
                    checkMenuConflict(entity, store, source.getConfigNo(), source.getDefinitionSnapshot(), effective);
                }
                var history = revisions.findObject(entity, source.getConfigKind(), source.getConfigNo());
                var row = new FnbConfigRevision(); row.setEntityNo(entity); row.setCreatedBy(actor);
                row.setRevisionNo(BizKey.next("FREV")); row.setConfigKind(source.getConfigKind());
                row.setConfigNo(source.getConfigNo());
                row.setRevision(Math.addExact(history.getFirst().getRevision(), 1));
                row.setContentSchema(source.getContentSchema());
                row.setDefinitionSnapshot(source.getDefinitionSnapshot());
                row.setContentHash(source.getContentHash()); row.setEffectiveAt(effective.toLocalDateTime());
                row.setScopeStoreNos(json.writeValueAsString(List.of(store)));
                row.setStatus(effective.isAfter(now) ? "SCHEDULED" : "EFFECTIVE");
                row.setPreviousRevisionNo(history.getFirst().getRevisionNo());
                if (revisions.append(row) != 1) throw new IllegalStateException("Missing republication append");
                advanceHead(entity, store, row.getConfigKind(), row.getConfigNo(), req.version(), row);
                var result = new PublishConfigResultVO(view(row, now));
                var audit = new FnbActionLog(); audit.setEntityNo(entity); audit.setStoreNo(store);
                audit.setActionNo(BizKey.next("FACT")); audit.setObjectType(row.getConfigKind());
                audit.setObjectNo(row.getConfigNo()); audit.setAction("REPUBLISH_CONFIG");
                audit.setOperatorNo(actor); audit.setReason(auditReason);
                audit.setRequestNo(FoodCommandFingerprint.hash(endpoint, key));
                audit.setBeforeRef(sourceNo); audit.setAfterRef(row.getRevisionNo()); actions.insert(audit);
                publisher.publish(new ConfigPublished(entity, store, audit.getActionNo(), result.publication()),
                        new PublishOptions("FOOD_CONFIG:" + row.getConfigKind() + ":" + row.getConfigNo(), 1));
                return result;
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException | ArithmeticException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void checkMenuConflict(String entity, String store, String number, String document,
            OffsetDateTime effective) {
        var candidate = json.readValue(document, MenuPublicationSnapshot.class).menu();
        if (candidate.definition().config().exclusiveGroup() == null) return;
        var timeline = revisions.menuTimeline(entity, store).stream().filter(r -> inStore(r, store)).toList();
        if (timeline.size() > 10000) throw BizException.of(ErrorCode.CONFLICT);
        var start = effective.toLocalDateTime();
        var end = timeline.stream().filter(r -> number.equals(r.getConfigNo()) && r.getEffectiveAt().isAfter(start))
                .map(FnbConfigRevision::getEffectiveAt).min(Comparator.naturalOrder()).orElse(null);
        var byObject = new TreeMap<String, List<FnbConfigRevision>>();
        timeline.forEach(r -> byObject.computeIfAbsent(r.getConfigNo(), n -> new ArrayList<>()).add(r));
        for (var group : byObject.entrySet()) {
            if (number.equals(group.getKey())) continue;
            var rows = group.getValue();
            for (int i = 0; i < rows.size(); i++) {
                var metadata = rows.get(i);
                var next = i + 1 < rows.size() ? rows.get(i + 1).getEffectiveAt() : null;
                if (next != null && !metadata.getEffectiveAt().isBefore(next)) continue;
                var from = metadata.getEffectiveAt().isAfter(start) ? metadata.getEffectiveAt() : start;
                var until = end == null ? next : next == null || end.isBefore(next) ? end : next;
                if (until != null && !from.isBefore(until)) continue;
                var row = revisions.findRevision(entity, metadata.getRevisionNo()); validateFrozen(row);
                var other = json.readValue(row.getDefinitionSnapshot(), MenuPublicationSnapshot.class).menu();
                if (MenuConflictPolicy.conflicts(candidate, other, from.toInstant(ZoneOffset.UTC),
                        until == null ? null : until.toInstant(ZoneOffset.UTC))) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
            }
        }
    }

    private record RepublicationReason(String reason, String approvalRef) { }

    private long currentVersion(String entity, String store, String kind, String number, boolean lock) {
        if ("MENU".equals(kind) && menus != null) return menus.currentVersion(entity, store, number, lock);
        if ("STATION".equals(kind) && stations != null) return stations.currentVersion(entity, store, number, lock);
        if ("RECIPE".equals(kind) && recipes != null) return recipes.currentVersion(entity, store, number, lock);
        if ("BUNDLE".equals(kind) && bundles != null) return bundles.currentVersion(entity, store, number, lock);
        if ("MODIFIER".equals(kind)) {
            var query = Wrappers.<FnbModifier>lambdaQuery().eq(FnbModifier::getEntityNo, entity)
                    .eq(FnbModifier::getModifierNo, number);
            if (lock) query.last("FOR UPDATE"); var row = modifiers.selectOne(query);
            if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
            if (!editable(row.getStatus()) || row.getVersion() == null) throw BizException.of(ErrorCode.CONFLICT);
            return row.getVersion();
        }
        if ("OPTION_GROUP".equals(kind)) {
            var query = Wrappers.<FnbOptionGroup>lambdaQuery().eq(FnbOptionGroup::getEntityNo, entity)
                    .eq(FnbOptionGroup::getGroupNo, number);
            if (lock) query.last("FOR UPDATE"); var row = groups.selectOne(query);
            if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
            if (!editable(row.getStatus()) || row.getVersion() == null) throw BizException.of(ErrorCode.CONFLICT);
            return row.getVersion();
        }
        throw BizException.of(ErrorCode.NOT_FOUND);
    }

    private void advanceHead(String entity, String store, String kind, String number, long version,
            FnbConfigRevision revision) {
        switch (kind) {
            case "MENU" -> menus.advance(entity, store, number, version, revision);
            case "STATION" -> stations.advance(entity, store, number, version, revision);
            case "RECIPE" -> recipes.advance(entity, store, number, version, revision);
            default -> advance(entity, kind, number, version, revision);
        }
    }

    private void revalidateHistorical(String entity, String store, FnbConfigRevision row, Set<String> visited) {
        if (visited.size() > 1000) throw bad();
        if (!visited.add(row.getRevisionNo())) return;
        validateFrozen(row);
        var tree = json.readTree(row.getDefinitionSnapshot());
        List<SkuIdentityFact> identities = json.readValue(tree.get("skuFacts").toString(),
                json.getTypeFactory().constructCollectionType(List.class, SkuIdentityFact.class));
        for (var expected : identities) {
            var current = goods.getSku(entity, store, expected.skuNo());
            if (current == null || !entity.equals(current.entityNo()) || !store.equals(current.storeNo())
                    || !expected.skuNo().equals(current.skuNo()) || !expected.goodsNo().equals(current.goodsNo())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            var product = goods.getGoods(entity, store, current.goodsNo());
            if (product == null || !entity.equals(product.entityNo()) || !store.equals(product.storeNo())
                    || !current.goodsNo().equals(product.goodsNo()) || !product.baseOnSale()
                    || !"APPROVED".equals(product.auditStatus()) || product.skus().stream()
                            .noneMatch(sku -> expected.skuNo().equals(sku.skuNo()))) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
        if (row.getContentSchema() == 1) {
            var body = json.readValue(row.getDefinitionSnapshot(), PublicationSnapshot.class);
            var refs = body.specFacts().stream().map(SpecValueFact::valueNo).sorted().toList();
            if (!refs.isEmpty()) validateSpecs(entity, refs, specs.getValues(entity, refs));
            for (var ref : body.dependencies()) {
                var child = revisions.findRevision(entity, ref.revisionNo());
                if (child == null || !ref.contentHash().equals(child.getContentHash())
                        || !ref.configKind().equals(child.getConfigKind()) ||
                                !ref.configNo().equals(child.getConfigNo())
                        || ref.revision() != child.getRevision() || !inStore(child, store)
                        || "CANCELLED".equals(child.getStatus())) throw BizException.of(ErrorCode.CONFLICT);
                revalidateHistorical(entity, store, child, visited);
            }
        } else if (row.getContentSchema() == 4) {
            var captured = json.readValue(row.getDefinitionSnapshot(), MenuPublicationSnapshot.class).menu();
            read.execute(tx -> {
                revalidateHistoricalMenuLocal(entity, store, captured, false);
                return null;
            });
            revalidateMenuAuthorities(entity, store, captured);
        } else if (row.getContentSchema() == 3) {
            revalidateRecipeMaterials(entity, RecipePublicationDocuments.validate(json, row.getConfigNo(),
                    row.getDefinitionSnapshot()));
        }
    }

    /** Rechecks persisted eligibility before remote reads and again under the append transaction's locks. */
    private void revalidateHistoricalMenuLocal(String entity, String store,
            MenuPublicationSnapshot.Captured captured, boolean lock) {
        for (var ref : captured.dependencies()) {
            // Serializable append reads hold shared locks on immutable revisions until commit.
            var current = revisions.findRevision(entity, ref.revisionNo());
            if (current == null || !entity.equals(current.getEntityNo())
                    || !ref.configKind().equals(current.getConfigKind())
                    || !ref.configNo().equals(current.getConfigNo())
                    || current.getRevision() == null || ref.revision() != current.getRevision()
                    || current.getContentSchema() == null || ref.contentSchema() != current.getContentSchema()
                    || !ref.contentHash().equals(current.getContentHash())
                    || !ref.definitionSnapshot().equals(current.getDefinitionSnapshot())
                    || !("EFFECTIVE".equals(current.getStatus()) || "SCHEDULED".equals(current.getStatus()))
                    || !inStore(current, store)) throw BizException.of(ErrorCode.CONFLICT);
            validateFrozen(current);
        }
        for (var expected : captured.channelBindings()) {
            if (channelBindings == null) throw BizException.of(ErrorCode.CONFLICT);
            var query = Wrappers.<FnbMenuChannelBinding>lambdaQuery()
                    .eq(FnbMenuChannelBinding::getEntityNo, entity)
                    .eq(FnbMenuChannelBinding::getStoreNo, store)
                    .eq(FnbMenuChannelBinding::getBindingNo, expected.bindingNo());
            if (lock) query.last("FOR UPDATE");
            var current = DataScopeContext.executeWithoutScope(() -> channelBindings.selectOne(query));
            if (current == null || !entity.equals(current.getEntityNo()) || !store.equals(current.getStoreNo())
                    || !expected.bindingNo().equals(current.getBindingNo())
                    || !expected.menuNo().equals(current.getMenuNo())
                    || !expected.provider().equals(current.getProvider())
                    || !expected.channelAccountRef().equals(current.getChannelAccountRef())
                    || !expected.externalStoreRef().equals(current.getExternalStoreRef())
                    || !"ACTIVE".equals(current.getStatus())
                    || current.getVersion() == null || current.getVersion() < 0) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
        }
    }

    private void revalidateRecipeMaterials(String entity, RecipePublicationSnapshot body) {
        var refs = body.materialFacts().stream().map(MaterialFact::itemId).sorted().toList();
        var current = materials.getMaterials(entity, refs);
        if (current == null || current.size() != refs.size()) throw BizException.of(ErrorCode.CONFLICT);
        var facts = new TreeMap<String, MaterialFact>();
        for (var fact : current) {
            if (!MaterialCatalogFacts.validFact(fact) || !entity.equals(fact.entityNo())
                    || !refs.contains(fact.itemId()) || facts.put(fact.itemId(), fact) != null) throw bad();
        }
        for (var fact : body.materialFacts()) if (!fact.definitionHash().equals(
                facts.get(fact.itemId()).definitionHash())) throw BizException.of(ErrorCode.CONFLICT);
    }

    private record Candidate(long version, ModifierConfigVO modifier, OptionGroupConfigVO optionGroup,
            BundleConfigVO bundle,
            List<Dependency> dependencies, List<String> skuNos, List<String> specNos,
            RecipePublicationSource.Captured recipe, MenuPublicationSnapshot.Captured menu,
            StationPublicationSource.Captured station) { }

    private Candidate capture(String entity, String store, String kind, String number, OffsetDateTime at, boolean lock) {
        return capture(entity, store, kind, number, at, lock, null);
    }
    private Candidate capture(String entity, String store, String kind, String number, OffsetDateTime at, boolean lock,
            List<GoodsFact> fullGoods) {
        if ("MENU".equals(kind)) {
            if (menus == null) throw BizException.of(ErrorCode.NOT_FOUND);
            var value = menus.capture(entity, store, number, lock, (k, n) -> selected(entity, store, k, n, at),
                    revision -> revisions.findRevision(entity, revision), fullGoods);
            return new Candidate(value.definition().config().version(), null, null, null, List.of(),
                    menus.skuNos(value), List.of(), null, value, null);
        }
        if ("STATION".equals(kind)) {
            if (stations == null) throw BizException.of(ErrorCode.NOT_FOUND);
            var value = stations.capture(entity, store, number, lock);
            return new Candidate(value.definition().version(), null, null, null, List.of(),
                    value.skuNos(), List.of(), null, null, value);
        }
        var deps = new ArrayList<Dependency>(); var skuNos = new TreeSet<String>(); var specNos = new TreeSet<String>();
        if ("RECIPE".equals(kind)) {
            if (recipes == null) throw BizException.of(ErrorCode.NOT_FOUND);
            var value = recipes.capture(entity, store, number, lock,
                    group -> selected(entity, store, "OPTION_GROUP", group, at));
            return new Candidate(value.recipe().version(), null, null, null, List.of(),
                    List.of(value.recipe().skuNo()), List.of(), value, null, null);
        }
        if ("BUNDLE".equals(kind)) {
            if (bundles == null) throw BizException.of(ErrorCode.NOT_FOUND);
            var value = bundles.capture(entity, number, lock);
            return new Candidate(value.definition().version(), null, null, value.definition(), List.of(),
                    value.skuNos(), List.of(), null, null, null);
        }
        if ("MODIFIER".equals(kind)) {
            var query = Wrappers.<FnbModifier>lambdaQuery().eq(FnbModifier::getEntityNo, entity)
                    .eq(FnbModifier::getModifierNo, number);
            if (lock) query.last("FOR UPDATE");
            var row = modifiers.selectOne(query);
            if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
            if (!editable(row.getStatus()) || !validKey(row.getSalesSkuNo(), 32)) throw bad();
            skuNos.add(row.getSalesSkuNo());
            var children = components.selectList(Wrappers.<FnbModifierComponent>lambdaQuery()
                    .eq(FnbModifierComponent::getEntityNo, entity).eq(FnbModifierComponent::getComboNo, number)
                    .orderByAsc(FnbModifierComponent::getMemberNo));
            if (!("ADDON".equals(row.getKind()) || "COMBO".equals(row.getKind())) || children.size() > 100
                    || "ADDON".equals(row.getKind()) != children.isEmpty()) throw bad();
            var seen = new HashSet<String>();
            for (var child : children) {
                if (!seen.add(child.getMemberNo()) || child.getQuantity() == null || child.getQuantity().signum() <= 0
                        || child.getQuantity().stripTrailingZeros().scale() > 3
                        || child.getQuantity().compareTo(new BigDecimal("999999999.999")) > 0) throw bad();
                dependency(entity, store, child.getMemberNo(), at, true, deps, skuNos);
            }
            var definition = new ModifierConfigVO(number, row.getKind(), row.getVersion(), row.getStatus(),
                    new ModifierConfigVO.Data(row.getName(), row.getSalesSkuNo(), row.getPublishedRevisionNo(),
                            children.stream().map(p -> new ModifierConfigVO.Component(p.getComponentNo(),
                                    p.getMemberNo(), p.getQuantity())).toList()));
            return new Candidate(row.getVersion(), definition, null, null, deps, List.copyOf(skuNos),
                    List.of(), null, null, null);
        }
        var query = Wrappers.<FnbOptionGroup>lambdaQuery().eq(FnbOptionGroup::getEntityNo, entity)
                .eq(FnbOptionGroup::getGroupNo, number);
        if (lock) query.last("FOR UPDATE");
        var row = groups.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!editable(row.getStatus())) throw bad();
        var children = items.selectList(Wrappers.<FnbOptionGroupItem>lambdaQuery()
                .eq(FnbOptionGroupItem::getEntityNo, entity).eq(FnbOptionGroupItem::getGroupNo, number));
        var input = new ReplaceOptionGroupItemsReq(row.getVersion(), children.stream().map(p -> new OptionItemInput(
                p.getOptionItemNo(), p.getSourceKind(), p.getSpecValueNo(), p.getModifierNo(),
                p.getMinQty(), p.getMaxQty(), p.getDefaultQty(), p.getSortNo())).toList(), List.of(),
                json.readValue(row.getSelectionRules(), SelectionRules.class));
        var normalized = OptionSelectionPolicy.normalize(input);
        OptionPublicationPolicy.validate(row.getMinQty(), row.getMaxQty(), normalized);
        for (var item : normalized.items()) {
            if ("SPEC_VALUE".equals(item.sourceKind())) specNos.add(item.specValueNo());
            else dependency(entity, store, item.modifierNo(), at, false, deps, skuNos);
        }
        Map<String,Object> rules = json.readValue(json.writeValueAsString(normalized.selectionRules()),
                json.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        var definition = new OptionGroupConfigVO(number, "OPTION_GROUP", row.getVersion(), row.getStatus(),
                new OptionGroupConfigVO.Data(row.getName(), row.getMinQty(), row.getMaxQty(), row.getPublishedRevisionNo(),
                        rules, normalized.items().stream().map(p -> new OptionGroupConfigVO.Item(p.optionItemNo(),
                                p.sourceKind(), p.specValueNo(), p.modifierNo(), p.minQty(), p.maxQty(),
                                p.defaultQty(), p.sortNo())).toList()));
        deps.sort(Comparator.comparing(Dependency::configNo));
        return new Candidate(row.getVersion(), null, definition, null, deps, List.copyOf(skuNos),
                List.copyOf(specNos), null, null, null);
    }

    private void dependency(String entity, String store, String number, OffsetDateTime at, boolean addon,
            List<Dependency> refs, Set<String> skus) {
        var head = modifiers.selectOne(Wrappers.<FnbModifier>lambdaQuery().eq(FnbModifier::getEntityNo, entity)
                .eq(FnbModifier::getModifierNo, number));
        if (head == null || !editable(head.getStatus()) || addon && !"ADDON".equals(head.getKind())) throw bad();
        var row = selected(entity, store, "MODIFIER", number, at);
        if (row == null) throw bad();
        var frozen = frozen(row);
        if (frozen.modifier() == null || !number.equals(frozen.modifier().configNo())
                || addon && !"ADDON".equals(frozen.modifier().kind())) throw bad();
        refs.add(new Dependency(row.getRevisionNo(), "MODIFIER", number, row.getRevision(), row.getContentHash()));
        frozen.skuFacts().forEach(f -> skus.add(f.skuNo()));
        if (skus.size() > 256) throw bad();
    }

    private void advance(String entity, String kind, String number, long version, FnbConfigRevision revision) {
        if ("BUNDLE".equals(kind)) {
            bundles.advance(entity, number, version, revision);
            return;
        }
        if ("MODIFIER".equals(kind)) {
            var row = modifiers.selectOne(Wrappers.<FnbModifier>lambdaQuery().eq(FnbModifier::getEntityNo, entity)
                    .eq(FnbModifier::getModifierNo, number));
            row.setVersion(version); row.setStatus("EFFECTIVE".equals(revision.getStatus()) ? "ACTIVE" : "DRAFT");
            if ("EFFECTIVE".equals(revision.getStatus())) row.setPublishedRevisionNo(revision.getRevisionNo());
            if (modifiers.updateById(row) != 1) throw BizException.of(ErrorCode.CONFLICT);
        } else {
            var row = groups.selectOne(Wrappers.<FnbOptionGroup>lambdaQuery().eq(FnbOptionGroup::getEntityNo, entity)
                    .eq(FnbOptionGroup::getGroupNo, number));
            row.setVersion(version); row.setStatus("EFFECTIVE".equals(revision.getStatus()) ? "ACTIVE" : "DRAFT");
            if ("EFFECTIVE".equals(revision.getStatus())) row.setPublishedRevisionNo(revision.getRevisionNo());
            if (groups.updateById(row) != 1) throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public PageData<ConfigRevisionVO> listRevisions(String entity, String store, ListConfigRevisionsQuery query) {
        scope(entity, store);
        if (query == null) throw bad();
        kind(query.configKind()); identifier(query.configNo());
        int page = query.page() == null ? 1 : query.page(), size = query.size() == null ? 20 : query.size();
        if (page < 1 || size < 1 || size > 100) throw bad();
        OffsetDateTime at = now();
        return read.execute(tx -> {
            var rows = revisions.findObject(entity, query.configKind(), query.configNo()).stream()
                    .filter(r -> inStore(r, store)).toList();
            long offset = ((long) page - 1) * size;
            var results = rows.stream().skip(offset).limit(size).map(r -> view(r, at)).toList();
            return PageData.of(results, rows.size(), page, size);
        });
    }

    @Override
    public Proof verifyMenuResource(Query query) {
        MenuSourcePolicy.validateQuery(query);
        scope(query.entityNo(), query.storeNo());
        return read.execute(
                tx -> {
                    var at = now();
                    var row = revisions.findRevision(query.entityNo(), query.revisionNo());
                    if (row == null
                            || !"MENU".equals(row.getConfigKind())
                            || !query.menuNo().equals(row.getConfigNo())
                            || !inStore(row, query.storeNo()))
                        throw BizException.of(ErrorCode.NOT_FOUND);
                    if (!("EFFECTIVE".equals(row.getStatus())
                                    || "SCHEDULED".equals(row.getStatus()))
                            || row.getEffectiveAt().isAfter(at.toLocalDateTime()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    var until = at.plusSeconds(30);
                    if (query.mode() == Mode.CURRENT) {
                        var current =
                                selected(
                                        query.entityNo(),
                                        query.storeNo(),
                                        "MENU",
                                        query.menuNo(),
                                        at);
                        if (current == null || !row.getRevisionNo().equals(current.getRevisionNo()))
                            throw BizException.of(ErrorCode.CONFLICT);
                        for (var candidate :
                                revisions.findObject(query.entityNo(), "MENU", query.menuNo())) {
                            var effective = candidate.getEffectiveAt().atOffset(ZoneOffset.UTC);
                            if (!"CANCELLED".equals(candidate.getStatus())
                                    && inStore(candidate, query.storeNo())
                                    && effective.isAfter(at)
                                    && effective.isBefore(until)) until = effective;
                        }
                    }
                    validateFrozen(row);
                    var content =
                            new ConfigRevisionContentVO(view(row, at), row.getDefinitionSnapshot());
                    var resource = MenuSourcePolicy.project(json, query, content);
                    var proof = new Proof(1, query, content, resource, at, until, null);
                    return new Proof(
                            1,
                            query,
                            content,
                            resource,
                            at,
                            until,
                            MenuSourcePolicy.receipt(json, proof));
                });
    }

    @Override
    public ConfigRevisionContentVO getRevision(String entity, String store, String number) {
        scope(entity, store); identifier(number);
        return read.execute(tx -> {
            var row = revisions.findRevision(entity, number);
            if (row == null || !inStore(row, store)) throw BizException.of(ErrorCode.NOT_FOUND);
            validateFrozen(row);
            return new ConfigRevisionContentVO(view(row, now()), row.getDefinitionSnapshot());
        });
    }

    @Override
    public ConfigRevisionContentVO resolveRevision(String entity, String store, String kind, String number,
            OffsetDateTime asOf) {
        scope(entity, store); kind(kind); identifier(number);
        if (asOf == null) throw bad();
        return read.execute(tx -> {
            var row = selected(entity, store, kind, number, asOf.withOffsetSameInstant(ZoneOffset.UTC));
            if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
            validateFrozen(row);
            return new ConfigRevisionContentVO(view(row, asOf), row.getDefinitionSnapshot());
        });
    }

    private FnbConfigRevision selected(String entity, String store, String kind, String number, OffsetDateTime at) {
        return revisions.findObject(entity, kind, number).stream().filter(r -> !"CANCELLED".equals(r.getStatus())
                        && inStore(r, store)
                        && !r.getEffectiveAt().isAfter(at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()))
                .max(Comparator.comparing(FnbConfigRevision::getEffectiveAt).thenComparing(FnbConfigRevision::getRevision))
                .orElse(null);
    }

    private boolean inStore(FnbConfigRevision row, String store) {
        List<String> stores = json.readValue(row.getScopeStoreNos(),
                json.getTypeFactory().constructCollectionType(List.class, String.class));
        return stores.contains(store);
    }

    private PublicationSnapshot frozen(FnbConfigRevision row) {
        validateFrozen(row);
        if (row.getContentSchema() != 1) throw new IllegalStateException("Expected schema-one dependency");
        var snapshot = json.readValue(row.getDefinitionSnapshot(), PublicationSnapshot.class);
        if (snapshot.contentSchema() != 1 || !row.getConfigKind().equals(snapshot.configKind())
                || !row.getConfigNo().equals(snapshot.configNo())) throw new IllegalStateException("Invalid frozen identity");
        return snapshot;
    }

    private void validateFrozen(FnbConfigRevision row) {
        if (!hash(row.getDefinitionSnapshot()).equals(row.getContentHash())
                || PublicationDocuments.validate(json, row.getConfigKind(), row.getConfigNo(),
                row.getDefinitionSnapshot()) != row.getContentSchema()) {
            throw new IllegalStateException("Invalid frozen configuration document");
        }
        if ("MENU".equals(row.getConfigKind())) {
            var body = json.readValue(row.getDefinitionSnapshot(), MenuPublicationSnapshot.class);
            if (!row.getEntityNo().equals(body.menu().entityNo())
                    || !json.writeValueAsString(List.of(body.menu().storeNo())).equals(row.getScopeStoreNos())) {
                // Native JSON whitespace/key order is not an identity fact.
                List<String> scope = json.readValue(row.getScopeStoreNos(),
                        json.getTypeFactory().constructCollectionType(List.class, String.class));
                if (!row.getEntityNo().equals(body.menu().entityNo())
                        || !scope.equals(List.of(body.menu().storeNo()))) throw bad();
            }
        }
        if ("STATION".equals(row.getConfigKind())) {
            var body = StationPublicationDocuments.validate(json, row.getConfigNo(), row.getDefinitionSnapshot());
            List<String> scope = json.readValue(row.getScopeStoreNos(),
                    json.getTypeFactory().constructCollectionType(List.class, String.class));
            if (!row.getEntityNo().equals(body.entityNo()) || !scope.equals(List.of(body.storeNo()))) throw bad();
        }
        if ("RECIPE".equals(row.getConfigKind())) {
            var body = RecipePublicationDocuments.validate(json, row.getConfigNo(), row.getDefinitionSnapshot());
            List<String> stores = json.readValue(row.getScopeStoreNos(),
                    json.getTypeFactory().constructCollectionType(List.class, String.class));
            if (!stores.equals(List.of(body.recipe().storeNo()))) {
                throw new IllegalStateException("Invalid recipe publication store scope");
            }
            RecipePublicationDocuments.scope(body, row.getEntityNo(), body.recipe().storeNo());
        }
    }

    private void revalidateMenuAuthorities(String entity, String store, MenuPublicationSnapshot.Captured captured) {
        for (var binding : captured.channelBindings()) {
            var authority = channelAuthority.get();
            var contexts = captured.definition().contexts().stream()
                    .filter(c -> binding.bindingNo().equals(c.channelBindingNo())).toList();
            if (authority == null || !authority.authorized(entity, store, binding.provider(),
                    binding.channelAccountRef(), binding.externalStoreRef())
                    || contexts.stream().anyMatch(c -> !authority.pricePolicyAuthorized(entity, store,
                            binding.bindingNo(), c.pricePolicyRef()))) throw BizException.of(ErrorCode.CONFLICT);
        }
        for (var dependency : captured.dependencies()) {
            if (dependency.contentSchema() == 1) {
                var body = json.readValue(dependency.definitionSnapshot(), PublicationSnapshot.class);
                var refs = body.specFacts().stream().map(SpecValueFact::valueNo).sorted().toList();
                if (!refs.isEmpty()) validateSpecs(entity, refs, specs.getValues(entity, refs));
            } else if (dependency.contentSchema() == 3) {
                var body = RecipePublicationDocuments.validate(json, dependency.configNo(),
                        dependency.definitionSnapshot());
                var refs = body.materialFacts().stream().map(MaterialFact::itemId).sorted().toList();
                var current = materials.getMaterials(entity, refs);
                if (current == null || current.size() != refs.size()) throw BizException.of(ErrorCode.CONFLICT);
                var facts = new TreeMap<String, MaterialFact>();
                for (var fact : current) {
                    if (!MaterialCatalogFacts.validFact(fact)
                            || !entity.equals(fact.entityNo()) || !refs.contains(fact.itemId())
                            || facts.put(fact.itemId(), fact) != null) throw bad();
                }
                for (var fact : body.materialFacts()) if (!fact.definitionHash().equals(
                        facts.get(fact.itemId()).definitionHash())) throw BizException.of(ErrorCode.CONFLICT);
            }
        }
    }

    private static void validateSpecs(String entity, List<String> requested, List<SpecValueFact> facts) {
        if (facts == null) throw new IllegalStateException("Missing original specification facts");
        var seen = new HashSet<String>();
        for (var f : facts) {
            if (f == null || !requested.contains(f.valueNo()) || !seen.add(f.valueNo()) || !"ENUM".equals(f.valueType())
                    || !validKey(f.dimNo(), 64) || f.valueVersion() < 0 || f.dimVersion() < 0
                    || !visible(entity, f.valueScope(), f.valueEntityNo())
                    || !visible(entity, f.dimScope(), f.dimEntityNo())) throw bad();
        }
        if (seen.size() != requested.size()) throw bad();
    }

    private ConfigRevisionVO view(FnbConfigRevision row, OffsetDateTime at) {
        var effective = row.getEffectiveAt().atOffset(ZoneOffset.UTC);
        return new ConfigRevisionVO(row.getRevisionNo(), row.getConfigKind(), row.getConfigNo(), row.getRevision(),
                effective, row.getContentHash(), "CANCELLED".equals(row.getStatus()) ? "CANCELLED"
                        : effective.isAfter(at) ? "SCHEDULED" : "EFFECTIVE");
    }

    private String hash(String document) { return PublicationDocuments.hash(json, document); }
    private OffsetDateTime now() { return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MILLIS); }
    private static boolean visible(String entity, String scope, String owner) {
        return "PLATFORM".equals(scope) || "MERCHANT".equals(scope) && entity.equals(owner);
    }
    private static boolean editable(String status) { return "DRAFT".equals(status) || "ACTIVE".equals(status); }
    private static void scope(String entity, String store) {
        identifier(entity); identifier(store);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Publication cannot join an existing transaction");
        }
    }
    private static void kind(String value) {
        if (!("MODIFIER".equals(value) || "OPTION_GROUP".equals(value)
                || "BUNDLE".equals(value) || "RECIPE".equals(value)
                || "MENU".equals(value) || "STATION".equals(value))) throw bad();
    }
    private static void identifier(String value) { if (!validKey(value, 32)) throw bad(); }
    private static boolean validKey(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.trim());
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
}
