package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.RecipeLineVO;
import ai.neargo.shop.food.dto.RecipeResultVO;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbRecipe;
import ai.neargo.shop.food.entity.FnbRecipeLine;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.RecipeDefinitionPolicy;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.RecipeChanged;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.MaterialFact;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Store-owned drafts capture true base-unit definitions, never stock movements or invented conversions. */
public class RecipeServiceImpl implements RecipeService {
    private final RecipeMapper heads;
    private final RecipeLineMapper lines;
    private final ActionLogMapper actions;
    private final IdempotencyService idem;
    private final OutboxPublisher publisher;
    private final ObjectMapper json;
    private final GoodsCatalogQueryPort catalog;
    private final MaterialCatalogQueryPort materials;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public RecipeServiceImpl(RecipeMapper heads, RecipeLineMapper lines, ActionLogMapper actions,
            IdempotencyService idem, OutboxPublisher publisher, ObjectMapper json,
            PlatformTransactionManager manager, GoodsCatalogQueryPort catalog, MaterialCatalogQueryPort materials) {
        this.heads = heads; this.lines = lines; this.actions = actions; this.idem = idem;
        this.publisher = publisher; this.json = json; this.catalog = catalog; this.materials = materials;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public RecipeResultVO createRecipe(String entity, String store, String actor, CreateRecipeReq input, String key) {
        context(entity, store, actor, key);
        var req = RecipeDefinitionPolicy.normalize(input);
        String scope = FoodCommandFingerprint.scope("CREATE_RECIPE", entity, store);
        String hash = FoodCommandFingerprint.hash("create-recipe:v1", json.writeValueAsString(req));
        var replay = completed(key, scope, actor, hash);
        if (replay.isPresent()) return replay.get();
        sku(entity, store, req.skuNo());
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, RecipeResultVO.class, () -> {
                var row = new FnbRecipe();
                row.setEntityNo(entity); row.setStoreNo(store); row.setRecipeNo(BizKey.next("FRCP"));
                row.setSkuNo(req.skuNo()); row.setRecipeCode(req.recipeCode()); row.setYieldQty(req.yieldQty());
                row.setYieldUnit(req.yieldUnit()); row.setConfigVersion(0L); row.setVersion(0L); row.setStatus("DRAFT");
                heads.insert(row);
                var result = new RecipeResultVO(view(entity, row));
                record(entity, store, actor, key, "CREATE_RECIPE", null, result.recipe());
                return result;
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Override
    public RecipeResultVO replaceDefinition(String entity, String store, String number, String actor,
            ReplaceRecipeDefinitionReq input, String key) {
        context(entity, store, actor, key); RecipeDefinitionPolicy.identifier(number);
        var req = RecipeDefinitionPolicy.normalize(input);
        String scope = FoodCommandFingerprint.scope("REPLACE_RECIPE_DEFINITION", entity, store);
        String hash = FoodCommandFingerprint.hash("replace-recipe:v1", number, json.writeValueAsString(req));
        var replay = completed(key, scope, actor, hash);
        if (replay.isPresent()) return replay.get();
        var original = read.execute(tx -> head(entity, store, number, false));
        sku(entity, store, original.getSkuNo());
        var facts = facts(entity, req);
        try {
            return write.execute(tx -> idem.execute(key, scope, actor, hash, RecipeResultVO.class, () -> {
                var row = head(entity, store, number, true);
                if (!req.version().equals(row.getVersion()) || row.getVersion() == Long.MAX_VALUE
                        || row.getConfigVersion() == null || row.getConfigVersion() == Long.MAX_VALUE
                        || !original.getSkuNo().equals(row.getSkuNo()) || !"DRAFT".equals(row.getStatus())) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                var before = view(entity, row);
                var old = new HashMap<String, RecipeLineVO>();
                before.lines().forEach(line -> old.put(line.lineNo(), line));
                for (var line : req.lines()) {
                    if (line.lineNo() != null && (!old.containsKey(line.lineNo())
                            || !old.get(line.lineNo()).itemRef().equals(line.itemRef()))) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                }
                lines.replaceDraftRows(entity, number);
                int order = 0;
                for (var inputLine : req.lines()) {
                    var line = new FnbRecipeLine();
                    line.setEntityNo(entity); line.setRecipeNo(number);
                    line.setLineNo(inputLine.lineNo() == null ? BizKey.next("FRLN") : inputLine.lineNo());
                    line.setItemRef(inputLine.itemRef()); line.setQty(inputLine.qty()); line.setUnit(inputLine.unit());
                    line.setLossRate(inputLine.lossRate()); line.setSortNo(order++);
                    line.setMaterialDefinitionHash(facts.get(inputLine.itemRef()).definitionHash());
                    lines.insert(line);
                }
                row.setYieldQty(req.yieldQty()); row.setYieldUnit(req.yieldUnit());
                row.setConfigVersion(row.getConfigVersion() + 1);
                if (heads.updateById(row) != 1) throw BizException.of(ErrorCode.CONFLICT);
                var result = new RecipeResultVO(view(entity, row));
                record(entity, store, actor, key, "REPLACE_RECIPE_DEFINITION", before, result.recipe());
                return result;
            }));
        } catch (ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private Map<String, MaterialFact> facts(String entity, ReplaceRecipeDefinitionReq req) {
        var ids = req.lines().stream().map(line -> line.itemRef()).distinct().sorted().toList();
        if (ids.isEmpty()) return Map.of();
        var response = materials.getMaterials(entity, ids);
        if (response == null || response.size() > ids.size()) throw new IllegalStateException("Invalid material facts");
        var facts = new HashMap<String, MaterialFact>();
        for (var fact : response) {
            if (!MaterialCatalogFacts.validFact(fact) || !entity.equals(fact.entityNo())
                    || !ids.contains(fact.itemId()) || facts.putIfAbsent(fact.itemId(), fact) != null) {
                throw new IllegalStateException("Invalid material facts");
            }
        }
        for (var line : req.lines()) {
            var fact = facts.get(line.itemRef());
            if (fact == null || !line.unit().equals(fact.baseUom())) throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return facts;
    }

    @Override
    public RecipeResultVO getRecipe(String entity, String store, String number) {
        queryContext(entity, store); RecipeDefinitionPolicy.identifier(number);
        return read.execute(tx -> new RecipeResultVO(view(entity, head(entity, store, number, false))));
    }

    @Override
    public PageData<RecipeVO> listRecipes(String entity, String store, ListRecipesQuery query) {
        queryContext(entity, store);
        int page = query == null || query.page() == null ? 1 : query.page();
        int size = query == null || query.size() == null ? 20 : query.size();
        if (page < 1 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        String sku = query == null ? null : query.skuNo();
        String status = query == null ? null : query.status();
        if (sku != null) RecipeDefinitionPolicy.identifier(sku);
        if (status != null && !List.of("DRAFT", "ACTIVE", "DISABLED").contains(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return read.execute(tx -> {
            var result = heads.selectPage(new Page<FnbRecipe>(page, size), Wrappers.<FnbRecipe>lambdaQuery()
                    .eq(FnbRecipe::getEntityNo, entity).eq(FnbRecipe::getStoreNo, store)
                    .eq(sku != null, FnbRecipe::getSkuNo, sku).eq(status != null, FnbRecipe::getStatus, status)
                    .orderByAsc(FnbRecipe::getRecipeNo));
            return PageData.of(result.getRecords().stream().map(row -> view(entity, row)).toList(),
                    result.getTotal(), page, size);
        });
    }

    private FnbRecipe head(String entity, String store, String number, boolean lock) {
        var query = Wrappers.<FnbRecipe>lambdaQuery().eq(FnbRecipe::getEntityNo, entity)
                .eq(FnbRecipe::getStoreNo, store).eq(FnbRecipe::getRecipeNo, number);
        if (lock) query.last("FOR UPDATE");
        var row = heads.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private RecipeVO view(String entity, FnbRecipe row) {
        var children = lines.selectList(Wrappers.<FnbRecipeLine>lambdaQuery()
                .eq(FnbRecipeLine::getEntityNo, entity).eq(FnbRecipeLine::getRecipeNo, row.getRecipeNo())
                .orderByAsc(FnbRecipeLine::getSortNo, FnbRecipeLine::getLineNo));
        return new RecipeVO(row.getRecipeNo(), row.getStoreNo(), row.getSkuNo(), row.getRecipeCode(),
                RecipeDefinitionPolicy.quantity(row.getYieldQty(), true), row.getYieldUnit(), row.getStatus(),
                row.getVersion(), row.getConfigVersion(), children.stream().map(line -> new RecipeLineVO(
                        line.getLineNo(), line.getItemRef(), RecipeDefinitionPolicy.quantity(line.getQty(), true),
                        line.getUnit(), RecipeDefinitionPolicy.quantity(line.getLossRate(), false),
                        line.getMaterialDefinitionHash())).toList());
    }

    private void sku(String entity, String store, String number) {
        var fact = catalog.getSku(entity, store, number);
        if (fact == null || !entity.equals(fact.entityNo()) || !store.equals(fact.storeNo())
                || !number.equals(fact.skuNo()) || !MaterialCatalogFacts.validKey(fact.goodsNo(), 32)
                || fact.version() < 0) throw new IllegalStateException("Invalid original SKU identity");
    }

    private Optional<RecipeResultVO> completed(String key, String scope, String actor, String hash) {
        return read.execute(tx -> idem.findCompleted(key, scope, actor, hash, RecipeResultVO.class));
    }

    private void record(String entity, String store, String actor, String key, String action,
            RecipeVO before, RecipeVO after) {
        var audit = new FnbActionLog(); audit.setEntityNo(entity); audit.setStoreNo(store);
        audit.setActionNo(BizKey.next("FACT")); audit.setObjectType("RECIPE"); audit.setObjectNo(after.recipeNo());
        audit.setAction(action); audit.setOperatorNo(actor);
        audit.setRequestNo(FoodCommandFingerprint.hash(FoodCommandFingerprint.scope(action, entity, store), key));
        if (before != null) audit.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(before)));
        audit.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after))); actions.insert(audit);
        publisher.publish(new RecipeChanged(entity, store, after.recipeNo(), action, after.version(),
                audit.getActionNo(), after), new PublishOptions("FOOD_RECIPE:" + after.recipeNo(), 1));
    }

    private static void context(String entity, String store, String actor, String key) {
        queryContext(entity, store); RecipeDefinitionPolicy.identifier(actor);
        if (key == null || key.isBlank() || key.length() > 128) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static void queryContext(String entity, String store) {
        RecipeDefinitionPolicy.identifier(entity); RecipeDefinitionPolicy.identifier(store);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Recipe operations cannot join an existing transaction");
        }
    }
}
