package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.HashSet;
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

    public DishServiceImpl(DishMapper dishes, ActionLogMapper actions, IdempotencyService idempotency,
            OutboxPublisher publisher, ObjectMapper json, PlatformTransactionManager manager,
            GoodsCatalogQueryPort catalog) {
        this.dishes = dishes;
        this.actions = actions;
        this.idempotency = idempotency;
        this.publisher = publisher;
        this.json = json;
        this.catalog = catalog;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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
        // Custom media awaits an ownership-capable asset contract; the base cover stays in the product facts.
        if (!p.imageRefs().isEmpty() || p.localizedNames().size() > 20) throw BizException.of(ErrorCode.BAD_REQUEST);
        var locales = new HashSet<String>();
        var names = p.localizedNames().stream().map(n -> {
            String locale = text(n.locale(), 35).toLowerCase(Locale.ROOT);
            if (!locale.matches("[a-z]{2,8}(-[a-z0-9]{1,8})*") || !locales.add(locale)) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            return new LocalizedName(locale, text(n.name(), 128));
        }).toList();
        String name = p.displayName() == null || p.displayName().isBlank() ? null : text(p.displayName(), 128);
        var presentation = new DishPresentation(name, names, List.of(), texts(p.tags(), 20, 32),
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
