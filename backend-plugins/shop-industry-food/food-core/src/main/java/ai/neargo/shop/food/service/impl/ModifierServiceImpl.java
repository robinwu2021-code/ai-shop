package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.food.service.CatalogCurrencyService;
import ai.neargo.shop.food.policy.CatalogCurrencySession;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.CreateModifierResultVO;
import ai.neargo.shop.food.dto.ListModifiersQuery;
import ai.neargo.shop.food.dto.ModifierComponentInput;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.ModifierMutationVO;
import ai.neargo.shop.food.dto.UpdateModifierReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbModifier;
import ai.neargo.shop.food.entity.FnbModifierComponent;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.ModifierChanged;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Owns editable serving composition, while original product services retain SKU and financial authority. */
public class ModifierServiceImpl implements ModifierService {
    private final CatalogCurrencyService currencies;
    private final ModifierMapper heads;
    private final ModifierComponentMapper components;
    private final ActionLogMapper actions;
    private final IdempotencyService idem;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final GoodsCatalogQueryPort catalog;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public ModifierServiceImpl(ModifierMapper heads, ModifierComponentMapper components, ActionLogMapper actions,
            IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort catalog) {
        this(heads, components, actions, idem, publisher, json, manager, catalog, (entity, store) ->
                CatalogCurrencySession.legacy(catalog, entity, store));
    }

    /** Currency-aware wiring keeps legacy construction and original command semantics available. */
    public ModifierServiceImpl(ModifierMapper heads, ModifierComponentMapper components, ActionLogMapper actions,
            IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort catalog,
            CatalogCurrencyService currencies) {
        this.currencies = currencies;
        this.heads = heads;
        this.components = components;
        this.actions = actions;
        this.idem = idem;
        this.publisher = publisher;
        this.json = json;
        this.catalog = catalog;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public CreateModifierResultVO createModifier(String entity, String store, String actor,
            CreateModifierReq input, String key) {
        context(entity, store, actor, key);
        var req = normalize(input);
        var currency = currencies.open(entity, store);
        sku(entity, store, req.salesSkuNo(), currency);
        currency.refresh();
        String scope = FoodCommandFingerprint.scope("CREATE_MODIFIER", entity, store);
        String hash = FoodCommandFingerprint.hash("create-modifier:v1", json.writeValueAsString(req));
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, CreateModifierResultVO.class, () -> {
                currency.guardTransaction();
                validateMembers(entity, req);
                var row = new FnbModifier();
                row.setEntityNo(entity);
                row.setModifierNo(BizKey.next("FMOD"));
                row.setKind(req.kind());
                row.setName(req.name());
                row.setSalesSkuNo(req.salesSkuNo());
                row.setInventoryMode(req.inventoryMode());
                row.setVersion(0L);
                row.setStatus("DRAFT");
                heads.insert(row);
                replaceComponents(entity, row.getModifierNo(), req.components());
                record(entity, store, actor, key, "CREATE_MODIFIER", null, views(entity, List.of(row)).getFirst());
                return new CreateModifierResultVO(row.getModifierNo(), row.getVersion(), row.getStatus());
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public ModifierMutationVO updateModifier(String entity, String store, String number, String actor,
            UpdateModifierReq input, String key) {
        context(entity, store, actor, key);
        identifier(number);
        if (input == null || input.version() == null || input.version() < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var req = normalize(new CreateModifierReq(input.name(), input.kind(), input.salesSkuNo(), input.components(), input.inventoryMode()));
        var currency = currencies.open(entity, store);
        sku(entity, store, req.salesSkuNo(), currency);
        currency.refresh();
        String scope = FoodCommandFingerprint.scope("UPDATE_MODIFIER", entity, store);
        String hash = FoodCommandFingerprint.hash("update-modifier:v1", number, input.version().toString(),
                json.writeValueAsString(req));
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, ModifierMutationVO.class, () -> {
                currency.guardTransaction();
                var row = heads.selectOne(Wrappers.<FnbModifier>lambdaQuery().eq(FnbModifier::getEntityNo, entity)
                        .eq(FnbModifier::getModifierNo, number).last("FOR UPDATE"));
                if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
                if (!input.version().equals(row.getVersion()) || row.getVersion() == Long.MAX_VALUE
                        || !req.kind().equals(row.getKind()) || !editable(row.getStatus())) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                var before = views(entity, List.of(row)).getFirst();
                try {
                    ai.neargo.shop.food.policy.ModifierInventoryMode.resolve(req.kind(), req.salesSkuNo(),
                            req.inventoryMode() == null ? row.getInventoryMode() : req.inventoryMode());
                } catch (IllegalArgumentException invalid) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
                validateMembers(entity, req);
                row.setName(req.name());
                row.setSalesSkuNo(req.salesSkuNo());
                if (req.inventoryMode() != null) row.setInventoryMode(req.inventoryMode());
                row.setStatus("DRAFT");
                // ALWAYS strategy on salesSkuNo makes explicit null clear the old reference.
                if (heads.updateById(row) != 1) throw BizException.of(ErrorCode.CONFLICT);
                replaceComponents(entity, number, req.components());
                record(entity, store, actor, key, "UPDATE_MODIFIER", before, views(entity, List.of(row)).getFirst());
                return new ModifierMutationVO(number, row.getVersion());
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public PageData<ModifierConfigVO> listModifiers(String entity, String store, ListModifiersQuery query) {
        identifier(entity);
        identifier(store);
        noOuterTransaction();
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        if (page < 1 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        return read.execute(tx -> {
            var result = heads.selectPage(new Page<FnbModifier>(page, size), Wrappers.<FnbModifier>lambdaQuery()
                    .eq(FnbModifier::getEntityNo, entity).orderByAsc(FnbModifier::getModifierNo));
            return PageData.of(views(entity, result.getRecords()), result.getTotal(), page, size);
        });
    }

    private void validateMembers(String entity, CreateModifierReq req) {
        for (var part : req.components()) {
            var member = heads.selectOne(Wrappers.<FnbModifier>lambdaQuery().eq(FnbModifier::getEntityNo, entity)
                    .eq(FnbModifier::getModifierNo, part.memberNo()).last("FOR UPDATE"));
            if (member == null || !entity.equals(member.getEntityNo())
                    || !part.memberNo().equals(member.getModifierNo())
                    || !"ADDON".equals(member.getKind()) || !editable(member.getStatus())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }

    private void replaceComponents(String entity, String number, List<ModifierComponentInput> inputs) {
        components.replaceDraftRows(entity, number);
        for (var input : inputs) {
            var row = new FnbModifierComponent();
            row.setEntityNo(entity);
            row.setComponentNo(BizKey.next("FMCP"));
            row.setComboNo(number);
            row.setMemberNo(input.memberNo());
            row.setQuantity(input.quantity());
            components.insert(row);
        }
    }

    private void sku(String entity, String store, String number, CatalogCurrencySession currency) {
        if (number == null) return;
        var fact = currency.sku(number);
        if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                || !number.equals(fact.skuNo()) || fact.goodsNo() == null || fact.goodsNo().isBlank()
                || fact.goodsNo().length() > 32 || !fact.goodsNo().equals(fact.goodsNo().trim())
                || fact.version() < 0) {
            throw new IllegalStateException("Invalid original SKU identity");
        }
    }

    private List<ModifierConfigVO> views(String entity, List<FnbModifier> rows) {
        if (rows.isEmpty()) return List.of();
        var numbers = rows.stream().map(FnbModifier::getModifierNo).toList();
        var children = components.selectList(Wrappers.<FnbModifierComponent>lambdaQuery()
                .eq(FnbModifierComponent::getEntityNo, entity).in(FnbModifierComponent::getComboNo, numbers)
                .orderByAsc(FnbModifierComponent::getMemberNo)).stream()
                .collect(Collectors.groupingBy(FnbModifierComponent::getComboNo));
        return rows.stream().map(row -> new ModifierConfigVO(
                row.getModifierNo(), row.getKind(), row.getVersion(),
                row.getStatus(), new ModifierConfigVO.Data(row.getName(), row.getSalesSkuNo(),
                        row.getPublishedRevisionNo(), children.getOrDefault(row.getModifierNo(), List.of()).stream()
                                .map(p -> new ModifierConfigVO.Component(p.getComponentNo(), p.getMemberNo(),
                                        p.getQuantity())).toList(), row.getInventoryMode()))).toList();
    }

    private void record(String entity, String store, String actor, String key, String action,
            ModifierConfigVO before, ModifierConfigVO after) {
        var audit = new FnbActionLog();
        audit.setEntityNo(entity);
        audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT"));
        audit.setObjectType("MODIFIER");
        audit.setObjectNo(after.configNo());
        audit.setAction(action);
        audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(FoodCommandFingerprint.scope(action, entity, store), key));
        if (before != null) audit.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(before)));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after)));
        actions.insert(audit);
        publisher.publish(new ModifierChanged(entity, store, after.configNo(), action, after.version(),
                audit.getActionNo(), after), new PublishOptions("FOOD_MODIFIER:" + after.configNo(), 1));
    }

    private static CreateModifierReq normalize(CreateModifierReq input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().trim().length() > 64
                || !("ADDON".equals(input.kind()) || "COMBO".equals(input.kind()))
                || input.components() == null || input.components().size() > 100) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (input.salesSkuNo() != null) identifier(input.salesSkuNo());
        if ("ADDON".equals(input.kind()) != input.components().isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var seen = new HashSet<String>();
        var parts = input.components().stream().map(p -> {
            if (p == null) throw BizException.of(ErrorCode.BAD_REQUEST);
            identifier(p.memberNo());
            if (!seen.add(p.memberNo()) || p.quantity() == null || p.quantity().signum() <= 0) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            var qty = p.quantity().stripTrailingZeros();
            if (qty.scale() > 3 || qty.compareTo(new BigDecimal("999999999.999")) > 0) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            return new ModifierComponentInput(p.memberNo(), qty);
        }).sorted(Comparator.comparing(ModifierComponentInput::memberNo)).toList();
        if (input.inventoryMode() != null) {
            try {
                ai.neargo.shop.food.policy.ModifierInventoryMode.resolve(
                        input.kind(), input.salesSkuNo(), input.inventoryMode());
            } catch (IllegalArgumentException invalid) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            // Do not allow new member-based configurations until all order paths honor them.
            if ("COMPONENTS".equals(input.inventoryMode())) throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return new CreateModifierReq(input.name().trim(), input.kind(), input.salesSkuNo(), parts,
                input.inventoryMode());
    }

    private static boolean editable(String status) { return "DRAFT".equals(status) || "ACTIVE".equals(status); }

    private static void context(String entity, String store, String actor, String key) {
        identifier(entity);
        identifier(store);
        identifier(actor);
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
        noOuterTransaction();
    }

    private static void identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 32 || !value.equals(value.trim())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static void noOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Modifier operations cannot join an existing transaction");
        }
    }
}
