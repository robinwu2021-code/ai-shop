package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.OptionGroupMutationVO;
import ai.neargo.shop.food.entity.FnbDish;
import ai.neargo.shop.food.entity.FnbDishOptionGroup;
import ai.neargo.shop.food.entity.FnbModifier;
import ai.neargo.shop.food.mapper.FoodMappers.DishMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.policy.OptionSelectionPolicy;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import ai.neargo.shop.food.dto.CreateOptionGroupResultVO;
import ai.neargo.shop.food.dto.ListOptionGroupsQuery;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionGroupResultVO;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbOptionGroup;
import ai.neargo.shop.food.entity.FnbOptionGroupItem;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.OptionGroupChanged;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Reuses V1 shared group tables and the platform's atomic command infrastructure. */
public class OptionGroupServiceImpl implements OptionGroupService {
    private final DishMapper dishes;
    private final DishOptionGroupMapper bindings;
    private final ModifierMapper modifiers;
    private final GoodsCatalogQueryPort catalog;
    private final SpecValueCatalogQueryPort specs;
    private final OptionGroupMapper groups;
    private final OptionGroupItemMapper items;
    private final ActionLogMapper actions;
    private final IdempotencyService idempotency;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public OptionGroupServiceImpl(OptionGroupMapper groups, OptionGroupItemMapper items, ActionLogMapper actions,
            IdempotencyService idempotency, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, DishMapper dishes, DishOptionGroupMapper bindings,
            ModifierMapper modifiers, GoodsCatalogQueryPort catalog, SpecValueCatalogQueryPort specs) {
        this.dishes = dishes;
        this.bindings = bindings;
        this.modifiers = modifiers;
        this.catalog = catalog;
        this.specs = specs;
        this.groups = groups;
        this.items = items;
        this.actions = actions;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.json = json;
        this.writeTransaction = new TransactionTemplate(manager);
        this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTransaction = new TransactionTemplate(manager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public OptionGroupMutationVO replaceOptionGroupItems(String entity, String store, String group, String actor,
            ReplaceOptionGroupItemsReq input, String key) {
        identifier(entity);
        identifier(store);
        identifier(group);
        noOuterTransaction();
        if (actor == null || actor.isBlank() || actor.length() > 32 || !actor.equals(actor.trim())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        var req = OptionSelectionPolicy.normalize(input);
        var specNos = req.items().stream().filter(i -> "SPEC_VALUE".equals(i.sourceKind()))
                .map(i -> i.specValueNo()).toList();
        if (!specNos.isEmpty()) {
            var facts = specs.getValues(entity, specNos);
            var seen = new HashSet<String>();
            if (facts == null) throw new IllegalStateException("Missing specification facts");
            for (var fact : facts) {
                if (fact == null || !specNos.contains(fact.valueNo()) || !seen.add(fact.valueNo())
                        || !"ENUM".equals(fact.valueType()) || !visible(entity, fact.valueScope(), fact.valueEntityNo())
                        || !visible(entity, fact.dimScope(), fact.dimEntityNo())) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            if (seen.size() != specNos.size()) throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var previousLinks = bindings.selectList(Wrappers.<FnbDishOptionGroup>lambdaQuery()
                .eq(FnbDishOptionGroup::getEntityNo, entity).eq(FnbDishOptionGroup::getGroupNo, group));
        var requested = new HashSet<>(req.goodsNos());
        var candidates = new ArrayList<String>(previousLinks.stream().map(FnbDishOptionGroup::getGoodsNo)
                .collect(Collectors.toSet()));
        for (String number : req.goodsNos()) if (!candidates.contains(number)) candidates.add(number);
        var permitted = new HashSet<String>();
        for (int start = 0; start < candidates.size(); start += 256) {
            var chunk = candidates.subList(start, Math.min(start + 256, candidates.size()));
            var facts = catalog.getGoodsBatch(entity, store, chunk);
            if (facts == null) throw new IllegalStateException("Missing product facts");
            for (var fact : facts) {
                if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                        || !chunk.contains(fact.goodsNo()) || !permitted.add(fact.goodsNo())) {
                    throw new IllegalStateException("Invalid product facts");
                }
            }
        }
        if (!permitted.containsAll(requested)) throw BizException.of(ErrorCode.FORBIDDEN);
        String scope = FoodCommandFingerprint.scope("REPLACE_OPTION_ITEMS", entity, store);
        String hash = FoodCommandFingerprint.hash("replace-options:v1", group, json.writeValueAsString(req));
        try {
            return writeTransaction.execute(status -> idempotency.execute(key, scope, actor, hash,
                    OptionGroupMutationVO.class, () -> replace(entity, store, group, actor, key, req, permitted)));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private OptionGroupMutationVO replace(String entity, String store, String group, String actor, String key,
            ReplaceOptionGroupItemsReq req, Set<String> permitted) {
        var row = groups.selectOne(Wrappers.<FnbOptionGroup>lambdaQuery().eq(FnbOptionGroup::getEntityNo, entity)
                .eq(FnbOptionGroup::getGroupNo, group).last("FOR UPDATE"));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (!req.version().equals(row.getVersion()) || row.getVersion() == Long.MAX_VALUE
                || !("DRAFT".equals(row.getStatus()) || "ACTIVE".equals(row.getStatus()))) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        BigDecimal defaults = req.items().stream().map(i -> i.defaultQty()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal minima = req.items().stream().map(i -> i.minQty()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (defaults.compareTo(row.getMaxQty()) > 0 || minima.compareTo(row.getMaxQty()) > 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var before = views(entity, List.of(row)).getFirst();
        var oldItems = before.data().items().stream().collect(Collectors.toMap(i -> i.optionItemNo(), i -> i));
        for (var item : req.items()) {
            var previous = oldItems.get(item.optionItemNo());
            if (previous != null && (!previous.sourceKind().equals(item.sourceKind())
                    || !Objects.equals(previous.specValueNo(), item.specValueNo())
                    || !Objects.equals(previous.modifierNo(), item.modifierNo()))) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            if ("MODIFIER".equals(item.sourceKind())) {
                var modifier = modifiers.selectOne(Wrappers.<FnbModifier>lambdaQuery()
                        .eq(FnbModifier::getEntityNo, entity).eq(FnbModifier::getModifierNo, item.modifierNo()));
                if (modifier == null || !entity.equals(modifier.getEntityNo())
                        || !item.modifierNo().equals(modifier.getModifierNo())
                        || !("DRAFT".equals(modifier.getStatus()) || "ACTIVE".equals(modifier.getStatus()))
                        || !("ADDON".equals(modifier.getKind()) || "COMBO".equals(modifier.getKind()))) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
        }
        for (String goods : req.goodsNos()) {
            if (dishes.selectCount(Wrappers.<FnbDish>lambdaQuery().eq(FnbDish::getEntityNo, entity)
                    .eq(FnbDish::getGoodsNo, goods)) != 1) throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // Delete only mutable definition rows; published revisions and all fact/audit rows remain immutable.
        items.replaceDraftRows(entity, group);
        for (var item : req.items()) {
            var child = new FnbOptionGroupItem();
            child.setEntityNo(entity);
            child.setGroupNo(group);
            child.setOptionItemNo(item.optionItemNo());
            child.setSourceKind(item.sourceKind());
            child.setSpecValueNo(item.specValueNo());
            child.setModifierNo(item.modifierNo());
            child.setMinQty(item.minQty());
            child.setMaxQty(item.maxQty());
            child.setDefaultQty(item.defaultQty());
            child.setSortNo(item.sortNo());
            items.insert(child);
        }
        var links = bindings.selectList(Wrappers.<FnbDishOptionGroup>lambdaQuery()
                .eq(FnbDishOptionGroup::getEntityNo, entity).eq(FnbDishOptionGroup::getGroupNo, group));
        var linked = links.stream().map(FnbDishOptionGroup::getGoodsNo).collect(Collectors.toSet());
        for (var link : links) {
            if (permitted.contains(link.getGoodsNo()) && !req.goodsNos().contains(link.getGoodsNo())) {
                bindings.removeDraftBinding(entity, group, link.getGoodsNo());
            }
        }
        for (String goods : req.goodsNos()) if (!linked.contains(goods)) {
            var link = new FnbDishOptionGroup();
            link.setEntityNo(entity);
            link.setGoodsNo(goods);
            link.setGroupNo(group);
            link.setSortNo(0); bindings.insert(link);
        }
        row.setSelectionRules(json.writeValueAsString(req.selectionRules()));
        row.setStatus("DRAFT");
        if (groups.updateById(row) != 1) throw BizException.of(ErrorCode.CONFLICT);
        var after = views(entity, List.of(row)).getFirst();
        var audit = new FnbActionLog();
        audit.setEntityNo(entity);
        audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT"));
        audit.setObjectType("OPTION_GROUP");
        audit.setObjectNo(group);
        audit.setAction("REPLACE_OPTION_ITEMS");
        audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(
                FoodCommandFingerprint.scope("REPLACE_OPTION_ITEMS", entity, store), key));
        audit.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(before)));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after)));
        actions.insert(audit);
        publisher.publish(new OptionGroupChanged(entity, store, group, audit.getAction(), row.getVersion(),
                audit.getActionNo(), after), new PublishOptions("FOOD_OPTION_GROUP:" + group, 1));
        return new OptionGroupMutationVO(group, row.getVersion());
    }

    private static boolean visible(String entity, String scope, String owner) {
        return "PLATFORM".equals(scope) || "MERCHANT".equals(scope) && entity.equals(owner);
    }

    @Override
    public CreateOptionGroupResultVO createOptionGroup(String entity, String store, String actor,
            CreateOptionGroupReq req, String key) {
        identifier(entity);
        identifier(store);
        if (actor == null || actor.isBlank() || actor.length() > 32 || !actor.equals(actor.trim())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (req == null || req.name() == null || req.name().isBlank() || req.name().trim().length() > 64) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var normalized = new CreateOptionGroupReq(req.name().trim(), quantity(req.minQty()), quantity(req.maxQty()));
        if (normalized.minQty().compareTo(normalized.maxQty()) > 0) throw BizException.of(ErrorCode.BAD_REQUEST);
        noOuterTransaction();
        String scope = FoodCommandFingerprint.scope("CREATE_OPTION_GROUP", entity, store);
        String hash = FoodCommandFingerprint.hash("create-option-group:v1", json.writeValueAsString(normalized));
        try {
            return writeTransaction.execute(status -> idempotency.execute(key, scope, actor, hash,
                    CreateOptionGroupResultVO.class, () -> create(entity, store, actor, key, normalized)));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private CreateOptionGroupResultVO create(String entity, String store, String actor, String key,
            CreateOptionGroupReq req) {
        var row = new FnbOptionGroup();
        row.setEntityNo(entity);
        row.setGroupNo(BizKey.next("FOG"));
        row.setName(req.name());
        row.setMinQty(req.minQty());
        row.setMaxQty(req.maxQty());
        row.setSelectionRules("{}");
        row.setStatus("DRAFT");
        row.setVersion(0L);
        groups.insert(row);
        var after = views(entity, List.of(row)).getFirst();
        var audit = new FnbActionLog();
        audit.setEntityNo(entity);
        audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT"));
        audit.setObjectType("OPTION_GROUP");
        audit.setObjectNo(row.getGroupNo());
        audit.setAction("CREATE_OPTION_GROUP");
        audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(
                FoodCommandFingerprint.scope("CREATE_OPTION_GROUP", entity, store), key));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after)));
        actions.insert(audit);
        publisher.publish(new OptionGroupChanged(entity, store, row.getGroupNo(), audit.getAction(),
                row.getVersion(), audit.getActionNo(), after),
                new PublishOptions("FOOD_OPTION_GROUP:" + row.getGroupNo(), 1));
        return new CreateOptionGroupResultVO(row.getGroupNo(), row.getVersion(), row.getStatus());
    }

    @Override
    public OptionGroupResultVO getOptionGroup(String entity, String store, String config) {
        identifier(entity);
        identifier(store);
        identifier(config);
        noOuterTransaction();
        return readTransaction.execute(status -> {
            var row = groups.selectOne(Wrappers.<FnbOptionGroup>lambdaQuery()
                    .eq(FnbOptionGroup::getEntityNo, entity).eq(FnbOptionGroup::getGroupNo, config));
            if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
            return new OptionGroupResultVO(views(entity, List.of(row)).getFirst());
        });
    }

    @Override
    public PageData<OptionGroupConfigVO> listOptionGroups(String entity, String store, ListOptionGroupsQuery query) {
        identifier(entity);
        identifier(store);
        noOuterTransaction();
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        if (page < 1 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        return readTransaction.execute(status -> {
            var result = groups.selectPage(new Page<FnbOptionGroup>(page, size), Wrappers.<FnbOptionGroup>lambdaQuery()
                    .eq(FnbOptionGroup::getEntityNo, entity).orderByAsc(FnbOptionGroup::getGroupNo));
            return PageData.of(views(entity, result.getRecords()), result.getTotal(), page, size);
        });
    }

    private List<OptionGroupConfigVO> views(String entity, List<FnbOptionGroup> rows) {
        if (rows.isEmpty()) return List.of();
        var numbers = rows.stream().map(FnbOptionGroup::getGroupNo).toList();
        var children = items.selectList(Wrappers.<FnbOptionGroupItem>lambdaQuery()
                .eq(FnbOptionGroupItem::getEntityNo, entity).in(FnbOptionGroupItem::getGroupNo, numbers)
                .orderByAsc(FnbOptionGroupItem::getSortNo).orderByAsc(FnbOptionGroupItem::getOptionItemNo))
                .stream().collect(Collectors.groupingBy(FnbOptionGroupItem::getGroupNo));
        return rows.stream().map(row -> {
            var options = children.getOrDefault(row.getGroupNo(), List.of()).stream().map(item ->
                    new OptionGroupConfigVO.Item(item.getOptionItemNo(), item.getSourceKind(), item.getSpecValueNo(),
                            item.getModifierNo(), item.getMinQty(), item.getMaxQty(),
                            item.getDefaultQty(), item.getSortNo()))
                    .toList();
            Map<String, Object> rules = json.readValue(row.getSelectionRules(),
                    json.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            return new OptionGroupConfigVO(row.getGroupNo(), "OPTION_GROUP", row.getVersion(), row.getStatus(),
                    new OptionGroupConfigVO.Data(row.getName(), row.getMinQty(), row.getMaxQty(),
                            row.getPublishedRevisionNo(), rules, options));
        }).toList();
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() < 0) throw BizException.of(ErrorCode.BAD_REQUEST);
        var normalized = value.stripTrailingZeros();
        if (normalized.scale() > 6 || normalized.compareTo(new BigDecimal("999999999999.999999")) > 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return normalized.signum() == 0 ? BigDecimal.ZERO : normalized;
    }

    private static void identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 32 || !value.equals(value.trim())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Option-group operations cannot join an existing transaction");
        }
    }
}
