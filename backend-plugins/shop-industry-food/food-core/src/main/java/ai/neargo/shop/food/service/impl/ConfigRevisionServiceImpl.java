package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.PublicationSnapshot;
import ai.neargo.shop.food.dto.PublicationSnapshot.Dependency;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.PublishConfigResultVO;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.entity.FnbModifier;
import ai.neargo.shop.food.entity.FnbModifierComponent;
import ai.neargo.shop.food.entity.FnbOptionGroup;
import ai.neargo.shop.food.entity.FnbOptionGroupItem;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.OptionSelectionPolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.OptionPublicationPolicy;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.ConfigPublished;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;
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
        this.revisions = revisions; this.modifiers = modifiers; this.components = components;
        this.groups = groups; this.items = items; this.actions = actions; this.idem = idem;
        this.publisher = publisher; this.json = json; this.goods = goods; this.specs = specs; this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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
        var candidate = read.execute(tx -> capture(entity, store, kind, number, effective, false));
        var skuFacts = new ArrayList<SkuIdentityFact>();
        for (String sku : candidate.skuNos()) {
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
        try {
            return write.execute(tx -> idem.execute(key, endpoint, actor, fingerprint, PublishConfigResultVO.class, () -> {
                if (requested.isBefore(now.minusMinutes(5))) throw bad();
                var locked = capture(entity, store, kind, number, effective, true);
                if (locked.version() != req.version() || locked.version() == Long.MAX_VALUE
                        || !json.writeValueAsString(candidate).equals(json.writeValueAsString(locked))) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                var snapshot = new PublicationSnapshot(1, kind, number, locked.version(), locked.modifier(),
                        locked.optionGroup(), locked.dependencies(), skuFacts,
                        specFacts.stream().sorted(Comparator.comparing(SpecValueFact::valueNo)).toList(), approval);
                String document = PublicationDocuments.freeze(json, snapshot);
                if (document.getBytes(StandardCharsets.UTF_8).length > 1_048_576) throw bad();
                var history = revisions.findObject(entity, kind, number);
                long sequence = history.isEmpty() ? 1 : Math.addExact(history.getFirst().getRevision(), 1);
                var row = new FnbConfigRevision();
                row.setEntityNo(entity); row.setCreatedBy(actor); row.setRevisionNo(BizKey.next("FREV"));
                row.setConfigKind(kind); row.setConfigNo(number); row.setRevision(sequence); row.setContentSchema(1);
                row.setDefinitionSnapshot(document); row.setContentHash(hash(document));
                row.setEffectiveAt(effective.toLocalDateTime()); row.setScopeStoreNos(json.writeValueAsString(List.of(store)));
                row.setStatus(effective.isAfter(now) ? "SCHEDULED" : "EFFECTIVE");
                row.setPreviousRevisionNo(history.isEmpty() ? null : history.getFirst().getRevisionNo());
                if (revisions.append(row) != 1) throw new IllegalStateException("Missing publication append");
                advance(entity, kind, number, req.version(), row);
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

    private record Candidate(long version, ModifierConfigVO modifier, OptionGroupConfigVO optionGroup,
            List<Dependency> dependencies, List<String> skuNos, List<String> specNos) { }

    private Candidate capture(String entity, String store, String kind, String number, OffsetDateTime at, boolean lock) {
        var deps = new ArrayList<Dependency>(); var skuNos = new TreeSet<String>(); var specNos = new TreeSet<String>();
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
            return new Candidate(row.getVersion(), definition, null, deps, List.copyOf(skuNos), List.of());
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
        return new Candidate(row.getVersion(), null, definition, deps, List.copyOf(skuNos), List.copyOf(specNos));
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
    public ConfigRevisionContentVO getRevision(String entity, String store, String number) {
        scope(entity, store); identifier(number);
        return read.execute(tx -> {
            var row = revisions.findRevision(entity, number);
            if (row == null || !inStore(row, store)) throw BizException.of(ErrorCode.NOT_FOUND);
            frozen(row);
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
            frozen(row);
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
        if (row.getContentSchema() != 1 || !hash(row.getDefinitionSnapshot()).equals(row.getContentHash())) {
            throw new IllegalStateException("Invalid frozen configuration document");
        }
        var snapshot = json.readValue(row.getDefinitionSnapshot(), PublicationSnapshot.class);
        if (snapshot.contentSchema() != 1 || !row.getConfigKind().equals(snapshot.configKind())
                || !row.getConfigNo().equals(snapshot.configNo())) throw new IllegalStateException("Invalid frozen identity");
        return snapshot;
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
        if (!("MODIFIER".equals(value) || "OPTION_GROUP".equals(value))) throw bad();
    }
    private static void identifier(String value) { if (!validKey(value, 32)) throw bad(); }
    private static boolean validKey(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.trim());
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
}
