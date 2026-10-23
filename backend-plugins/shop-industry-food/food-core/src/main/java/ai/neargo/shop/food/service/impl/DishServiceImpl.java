package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.DishCatalogItemVO;
import ai.neargo.shop.food.dto.DishDetailResultVO;
import ai.neargo.shop.food.dto.ListDishesQuery;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.entity.FnbDishOptionGroup;
import ai.neargo.shop.food.entity.FnbOptionGroup;
import ai.neargo.shop.food.entity.FnbOptionGroupItem;
import ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsSummary;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishPresentation.LocalizedName;
import ai.neargo.shop.food.dto.DishResultVO;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.SaveDishReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbDish;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.DishChanged;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Product facts are read before the local atomic write; the restaurant never updates product price or stock. */
public class DishServiceImpl implements DishService {
    private final DishMapper dishes;
    private final ActionLogMapper actions;
    private final IdempotencyService idempotency;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final GoodsCatalogQueryPort catalog;
    private final MediaAssetQueryPort media;
    private final DishOptionGroupMapper bindings;
    private final OptionGroupMapper groups;
    private final OptionGroupItemMapper items;
    private final TransactionTemplate readTransaction;

    public DishServiceImpl(DishMapper dishes, ActionLogMapper actions, IdempotencyService idempotency,
            OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager,
            GoodsCatalogQueryPort catalog, DishOptionGroupMapper bindings, OptionGroupMapper groups,
            OptionGroupItemMapper items) {
        this(dishes, actions, idempotency, publisher, json, manager, catalog, bindings, groups, items,
                (entity, store, keys) -> { throw new IllegalStateException("Media ownership authority unavailable"); });
    }

    public DishServiceImpl(DishMapper dishes, ActionLogMapper actions, IdempotencyService idempotency,
            OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager,
            GoodsCatalogQueryPort catalog, DishOptionGroupMapper bindings, OptionGroupMapper groups,
            OptionGroupItemMapper items, MediaAssetQueryPort media) {
        this.media = media;
        this.dishes = dishes;
        this.actions = actions;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.json = json;
        this.catalog = catalog;
        this.bindings = bindings;
        this.groups = groups;
        this.items = items;
        this.readTransaction = new TransactionTemplate(manager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public PageData<DishCatalogItemVO> listDishes(String entity, String store, ListDishesQuery query) {
        identifier(entity); identifier(store); noOuterTransaction();
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        String kind = query == null ? null : query.dishKind();
        String word = query == null || query.keyword() == null ? "" : query.keyword().trim();
        if (page < 1 || size < 1 || size > 100 || word.length() > 128
                || kind != null && !Set.of("SINGLE", "BUNDLE", "ADDON").contains(kind)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String keyword = word.toLowerCase(Locale.ROOT);
        var rows = dishes.selectList(Wrappers.<FnbDish>lambdaQuery().eq(FnbDish::getEntityNo, entity)
                .eq(kind != null, FnbDish::getDishKind, kind).orderByAsc(FnbDish::getGoodsNo));
        var records = new ArrayList<DishCatalogItemVO>();
        long count = 0, offset = (long) (page - 1) * size;
        for (int start = 0; start < rows.size(); start += 256) {
            var chunk = rows.subList(start, Math.min(start + 256, rows.size()));
            var numbers = chunk.stream().map(FnbDish::getGoodsNo).toList();
            var facts = catalog.getGoodsBatch(entity, store, numbers);
            var byNumber = new HashMap<String, GoodsSummary>();
            if (facts == null) throw new IllegalStateException("Missing product batch response");
            for (var fact : facts) {
                if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                        || !numbers.contains(fact.goodsNo()) || byNumber.put(fact.goodsNo(), fact) != null) {
                    throw new IllegalStateException("Invalid product batch response");
                }
            }
            for (var row : chunk) {
                var fact = byNumber.get(row.getGoodsNo());
                if (fact == null) continue; // Only an explicit authoritative omission excludes this candidate.
                var dish = view(row);
                if (!keyword.isEmpty() && !contains(fact.title(), keyword)
                        && !contains(dish.presentation().displayName(), keyword)
                        && dish.presentation().localizedNames().stream().noneMatch(n -> contains(n.name(), keyword))) continue;
                if (count >= offset && records.size() < size) records.add(new DishCatalogItemVO(dish, fact));
                count++;
            }
        }
        return PageData.of(records, count, page, size);
    }

    @Override
    public DishDetailResultVO getDish(String entity, String store, String goods) {
        identifier(entity); identifier(store); identifier(goods); noOuterTransaction();
        var fact = catalog.getGoods(entity, store, goods);
        if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                || !goods.equals(fact.goodsNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        return readTransaction.execute(status -> {
            var row = dishes.selectOne(Wrappers.<FnbDish>lambdaQuery().eq(FnbDish::getEntityNo, entity)
                    .eq(FnbDish::getGoodsNo, goods));
            if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
            return new DishDetailResultVO(view(row), optionGroups(entity, goods), goods, fact);
        });
    }

    private List<OptionGroupConfigVO> optionGroups(String entity, String goods) {
        var links = bindings.selectList(Wrappers.<FnbDishOptionGroup>lambdaQuery()
                .eq(FnbDishOptionGroup::getEntityNo, entity).eq(FnbDishOptionGroup::getGoodsNo, goods)
                .orderByAsc(FnbDishOptionGroup::getSortNo).orderByAsc(FnbDishOptionGroup::getGroupNo));
        if (links.isEmpty()) return List.of();
        var numbers = links.stream().map(FnbDishOptionGroup::getGroupNo).toList();
        var heads = groups.selectList(Wrappers.<FnbOptionGroup>lambdaQuery().eq(FnbOptionGroup::getEntityNo, entity)
                .in(FnbOptionGroup::getGroupNo, numbers)).stream()
                .collect(Collectors.toMap(FnbOptionGroup::getGroupNo, g -> g));
        if (heads.size() != numbers.size()) throw BizException.of(ErrorCode.CONFLICT);
        var children = items.selectList(Wrappers.<FnbOptionGroupItem>lambdaQuery()
                .eq(FnbOptionGroupItem::getEntityNo, entity).in(FnbOptionGroupItem::getGroupNo, numbers)
                .orderByAsc(FnbOptionGroupItem::getSortNo).orderByAsc(FnbOptionGroupItem::getOptionItemNo))
                .stream().collect(Collectors.groupingBy(FnbOptionGroupItem::getGroupNo));
        return links.stream().map(link -> {
            var group = heads.get(link.getGroupNo());
            var options = children.getOrDefault(group.getGroupNo(), List.of()).stream().map(item ->
                    new OptionGroupConfigVO.Item(item.getOptionItemNo(), item.getSourceKind(), item.getSpecValueNo(),
                            item.getModifierNo(), item.getMinQty(), item.getMaxQty(), item.getDefaultQty(), item.getSortNo()))
                    .toList();
            Map<String, Object> rules = json.readValue(group.getSelectionRules(),
                    json.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            return new OptionGroupConfigVO(group.getGroupNo(), "OPTION_GROUP", group.getVersion(), group.getStatus(),
                    new OptionGroupConfigVO.Data(group.getName(), group.getMinQty(), group.getMaxQty(),
                            group.getPublishedRevisionNo(), rules, options));
        }).toList();
    }

    private static boolean contains(String value, String word) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(word);
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Dish query cannot perform remote lookups inside an existing transaction");
        }
    }

    @Override
    public DishResultVO saveDish(String entity, String store, String goods, String actor, SaveDishReq req, String key) {
        identifier(entity);
        identifier(store);
        identifier(goods);
        if (actor == null || actor.isBlank() || actor.length() > 32) throw BizException.of(ErrorCode.FORBIDDEN);
        text(key, 128);
        var normalized = normalize(req);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Dish command cannot join an existing transaction");
        }
        var fact = catalog.getGoods(entity, store, goods);
        if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                || !goods.equals(fact.goodsNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        verifyMedia(entity, store, normalized.presentation().imageRefs());
        // Draft management is allowed before audit/on-sale; it does not grant sellability.
        String scope = FoodCommandFingerprint.scope("SAVE_DISH", entity, store);
        String hash = FoodCommandFingerprint.hash("save-dish:v1", goods, json.writeValueAsString(normalized));
        try {
            return transaction.execute(status -> idempotency.execute(key, scope, actor, hash, DishResultVO.class,
                    () -> save(entity, store, goods, actor, key, normalized)));
        } catch (ConcurrencyFailureException | DuplicateKeyException conflict) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void verifyMedia(String entity, String store, List<String> keys) {
        if (keys.isEmpty()) return;
        MediaAssetQueryPort.validateLookup(entity, store, keys);
        var facts = media.getAssets(entity, store, keys);
        var seen = new HashSet<String>();
        if (facts == null || facts.size() > keys.size() || facts.stream().anyMatch(fact ->
                !MediaAssetQueryPort.validFact(fact, entity, store, keys) || !seen.add(fact.assetKey()))) {
            throw new IllegalStateException("Invalid media ownership response");
        }
        if (seen.size() != keys.size()) throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private DishResultVO save(String entity, String store, String goods, String actor, String key, SaveDishReq req) {
        var rows = dishes.selectList(Wrappers.<FnbDish>lambdaQuery().eq(FnbDish::getEntityNo, entity)
                .eq(FnbDish::getGoodsNo, goods).last("LIMIT 1 FOR UPDATE"));
        FnbDish row;
        DishVO before = null;
        if (req.version() == null) {
            if (!rows.isEmpty()) throw BizException.of(ErrorCode.CONFLICT);
            row = new FnbDish();
            row.setEntityNo(entity);
            row.setGoodsNo(goods);
            row.setVersion(0L);
            row.setConfigVersion(1L);
            row.setStatus("DRAFT");
        } else {
            if (rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            row = rows.getFirst();
            if (!req.version().equals(row.getVersion()) || row.getVersion() == Long.MAX_VALUE
                    || row.getConfigVersion() == Long.MAX_VALUE) throw BizException.of(ErrorCode.CONFLICT);
            before = view(row);
            row.setConfigVersion(row.getConfigVersion() + 1);
        }
        row.setDishKind(req.dishKind());
        row.setPrepSeconds(req.prepSeconds());
        row.setPresentation(json.writeValueAsString(req.presentation()));
        row.setMeasurementPolicy(json.writeValueAsString(req.measurement()));
        if (req.version() == null) dishes.insert(row);
        else if (dishes.updateById(row) != 1) throw BizException.of(ErrorCode.CONFLICT);
        var after = view(row);
        var audit = new FnbActionLog();
        audit.setEntityNo(entity);
        audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT"));
        audit.setObjectType("DISH");
        audit.setObjectNo(goods);
        audit.setAction(req.version() == null ? "CREATE_DISH" : "SAVE_DISH");
        audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(FoodCommandFingerprint.scope("SAVE_DISH", entity, store), key));
        audit.setBeforeRef(before == null ? null : snapshot(before));
        audit.setAfterRef(snapshot(after));
        actions.insert(audit);
        publisher.publish(new DishChanged(entity, store, goods, audit.getAction(), after.version(),
                audit.getActionNo(), before, after), new PublishOptions("FOOD_DISH:" + goods, 1));
        return new DishResultVO(after);
    }

    private String snapshot(DishVO value) { return FoodCommandFingerprint.hash(json.writeValueAsString(value)); }

    private DishVO view(FnbDish row) {
        return new DishVO(row.getGoodsNo(), row.getDishKind(), row.getPrepSeconds(),
                json.readValue(row.getPresentation(), DishPresentation.class),
                json.readValue(row.getMeasurementPolicy(), MeasurementPolicy.class),
                row.getConfigVersion(), row.getVersion());
    }

    private static SaveDishReq normalize(SaveDishReq req) {
        if (req == null || req.dishKind() == null || !Set.of("SINGLE", "BUNDLE", "ADDON").contains(req.dishKind())
                || req.prepSeconds() == null || req.prepSeconds() < 0 || req.presentation() == null
                || req.measurement() == null || req.version() != null && req.version() < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var p = req.presentation();
        if (p.localizedNames().size() > 20) throw BizException.of(ErrorCode.BAD_REQUEST);
        var imageRefs = texts(p.imageRefs(), 20, 512);
        if (imageRefs.stream().anyMatch(key -> !MediaAssetQueryPort.validKey(key))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var locales = new HashSet<String>();
        var names = p.localizedNames().stream().map(n -> {
            String locale = text(n.locale(), 35).toLowerCase(Locale.ROOT);
            if (!locale.matches("[a-z]{2,8}(-[a-z0-9]{1,8})*") || !locales.add(locale)) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            return new LocalizedName(locale, text(n.name(), 128));
        }).toList();
        String name = p.displayName() == null || p.displayName().isBlank() ? null : text(p.displayName(), 128);
        var presentation = new DishPresentation(name, names, imageRefs, texts(p.tags(), 20, 32),
                texts(p.allergenNotes(), 20, 255));
        var m = req.measurement();
        if (!"COUNT".equals(m.mode()) || !"EA".equals(m.unit()) || !Integer.valueOf(0).equals(m.scale())
                || m.allowedDeviation() != null && m.allowedDeviation().signum() != 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return new SaveDishReq(req.dishKind(), req.prepSeconds(), presentation,
                new MeasurementPolicy("EA", "COUNT", 0, null), req.version());
    }

    private static List<String> texts(List<String> values, int count, int length) {
        if (values.size() > count) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result = values.stream().map(v -> text(v, length)).toList();
        if (new HashSet<>(result).size() != result.size()) throw BizException.of(ErrorCode.BAD_REQUEST);
        return result;
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw BizException.of(ErrorCode.BAD_REQUEST);
        return value.trim();
    }

    private static void identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 32 || !value.equals(value.trim())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
